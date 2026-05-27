package reducer;

import model.*;
import logic.*;
import checker.ConstraintValidator;
import optimizer.ConstraintOptimizer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Shape reducer that applies constraint merging, simplification, and optimization
 * to a parsed ShapeGraph.
 * 
 * This is the second stage of the pipeline:
 *   Parser (SHACL → ShapeGraph) → Reducer (simplify) → Serializer (ShapeGraph → Turtle)
 * 
 * Corresponds to Python's reduce_shapes_processor() function.
 * 
 * Operations performed:
 * 1. Constraint merging: group PropertyConstraints by path, merge compatible ones
 * 2. Logic simplification: simplify boolean expressions using LogicNG
 * 3. Constraint optimization: remove redundancies, detect conflicts → FALSE
 */
public class ShapeReducer {
    private static final Logger log = LoggerFactory.getLogger(ShapeReducer.class);
    
    /** Threshold (ms) for logging per-step timing of a shape's reduction */
    private static final long STEP_TIMING_THRESHOLD_MS = 100;
    /** Threshold (ms) for warning about slow shape reductions */
    private static final long SLOW_SHAPE_THRESHOLD_MS = 1000;
    
    // Configuration options
    private boolean patternCacheEnabled = true;
    private boolean parallelEnabled = false;
    private int parallelism = Runtime.getRuntime().availableProcessors();
    
    // Components (created during reduce)
    private LogicSimplifier simplifier;
    private ConstraintMerger constraintMerger;
    private ConstraintOptimizer optimizer;
    private Model sourceModel;  // For xone structural deduplication
    
    // Conflict report: collects FALSE (unsatisfiable) constraints removed from output
    private ConflictReport conflictReport;
    
    public ShapeReducer() {
    }
    
    // ==================== Fluent Configuration ====================
    
    /**
     * Enable or disable pattern-based caching.
     * Pattern caching allows expressions with same structure but different symbols
     * to share cached simplification results (98%+ hit rate).
     * @param enabled true to enable pattern caching (default), false to disable
     * @return this reducer for fluent configuration
     */
    public ShapeReducer setPatternCacheEnabled(boolean enabled) {
        this.patternCacheEnabled = enabled;
        return this;
    }
    
    /**
     * Enable or disable parallel simplification.
     * Uses workload-balanced task distribution based on expression complexity.
     * @param enabled true to enable parallel processing, false for sequential (default)
     * @return this reducer for fluent configuration
     */
    public ShapeReducer setParallelEnabled(boolean enabled) {
        this.parallelEnabled = enabled;
        return this;
    }
    
    /**
     * Set the number of threads for parallel simplification.
     * Only effective when parallel is enabled.
     * @param threads number of threads (default: available processors)
     * @return this reducer for fluent configuration
     */
    public ShapeReducer setParallelism(int threads) {
        this.parallelism = threads;
        return this;
    }
    
    // ==================== Getters for statistics ====================
    
    /**
     * Get the simplifier (for accessing cache statistics).
     */
    public LogicSimplifier getSimplifier() {
        return simplifier;
    }
    
    /**
     * Get the constraint merger (for accessing merge statistics).
     */
    public ConstraintMerger getConstraintMerger() {
        return constraintMerger;
    }
    
    /**
     * Get the constraint optimizer (for accessing optimization statistics).
     */
    public ConstraintOptimizer getOptimizer() {
        return optimizer;
    }
    
    public boolean isParallelEnabled() {
        return parallelEnabled;
    }
    
    /**
     * Get the conflict report (available after reduce() completes).
     * Contains all FALSE (unsatisfiable) PropertyShapes that were removed from the output.
     */
    public ConflictReport getConflictReport() {
        return conflictReport;
    }
    
    // ==================== Core Reduction Logic ====================
    
    /**
     * Reduce (simplify) all shapes in the ShapeGraph.
     * This is the main entry point for the reduction stage.
     * 
     * @param shapeGraph the parsed ShapeGraph to reduce (modified in-place)
     * @return the same ShapeGraph, with constraints reduced
     */
    public ShapeGraph reduce(ShapeGraph shapeGraph) {
        log.info("Starting constraint reduction...");
        long startTime = System.currentTimeMillis();
        
        SymbolTable symbolTable = shapeGraph.getSymbolTable();
        if (symbolTable == null) {
            throw new IllegalArgumentException("ShapeGraph must have a SymbolTable (set during parsing)");
        }
        
        // Initialize components
        simplifier = new LogicSimplifier(symbolTable);
        simplifier.setPatternCacheEnabled(patternCacheEnabled);
        
        constraintMerger = new ConstraintMerger(symbolTable, simplifier);
        constraintMerger.setParallelEnabled(parallelEnabled);
        constraintMerger.setParallelism(parallelism);
        
        optimizer = new ConstraintOptimizer(symbolTable);
        
        // Initialize conflict report
        conflictReport = new ConflictReport();
        
        // Store source model for xone structural deduplication
        sourceModel = shapeGraph.getSourceModel();
        
        // Reduce all shapes
        List<NodeShape> shapes = new ArrayList<>(shapeGraph.getNodeShapes());
        
        if (parallelEnabled && shapes.size() > 1) {
            reduceShapesParallel(shapes);
        } else {
            reduceShapesSequential(shapes);
        }
        
        // Remove empty NodeShapes (all PropertyConstraints were FALSE)
        int removedShapes = removeEmptyNodeShapes(shapeGraph);
        
        long reduceTime = System.currentTimeMillis() - startTime;
        log.info("Reduction completed in {}ms. {} shapes processed.", reduceTime, shapes.size());
        
        // Log conflict summary
        if (conflictReport.hasConflicts()) {
            log.warn("Removed {} unsatisfiable PropertyShape(s) from {} NodeShape(s)",
                    conflictReport.getTotalConflicts(), conflictReport.getAffectedShapeCount());
            if (removedShapes > 0) {
                log.warn("Removed {} empty NodeShape(s) (all constraints were unsatisfiable)", removedShapes);
            }
        }
        
        return shapeGraph;
    }
    
    /**
     * Reduce constraints for all shapes sequentially.
     */
    private void reduceShapesSequential(List<NodeShape> shapes) {
        int total = shapes.size();
        for (int i = 0; i < total; i++) {
            NodeShape shape = shapes.get(i);
            int numPCs = shape.getPropertyConstraints().size();
            log.info("[{}/{}] Reducing shape: {} ({} property constraints)",
                    i + 1, total, shape.getUri(), numPCs);
            long shapeStart = System.currentTimeMillis();
            reduceShapeConstraints(shape, constraintMerger);
            long shapeTime = System.currentTimeMillis() - shapeStart;
            if (shapeTime > SLOW_SHAPE_THRESHOLD_MS) {
                log.warn("[{}/{}] Shape {} took {}ms ({} constraints)",
                        i + 1, total, shape.getUri(), shapeTime, numPCs);
            } else {
                log.info("[{}/{}] Shape {} done in {}ms", i + 1, total, shape.getUri(), shapeTime);
            }
        }
    }
    
    /**
     * Reduce constraints for all shapes in parallel using static partitioning.
     * Static partitioning provides best performance for uniformly distributed workloads.
     */
    private void reduceShapesParallel(List<NodeShape> shapes) {
        int numShapes = shapes.size();
        int workers = Math.min(parallelism, numShapes);
        
        log.info("Parallel reduction (static): {} shapes with {} workers", numShapes, workers);
        
        // Estimate workload for each shape
        int[] workloads = new int[numShapes];
        int totalWorkload = 0;
        for (int i = 0; i < numShapes; i++) {
            workloads[i] = estimateShapeWorkload(shapes.get(i));
            totalWorkload += workloads[i];
        }
        
        log.debug("Total workload: {}", totalWorkload);
        
        // Balanced partition using greedy algorithm
        List<List<Integer>> partitions = balancedPartition(numShapes, workloads, workers);
        
        // Execute partitions in parallel
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(workers);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        
        SymbolTable symbolTable = simplifier.getSymbolTable();
        
        for (List<Integer> partition : partitions) {
            futures.add(executor.submit(() -> {
                // Each thread gets its own simplifier and merger (thread-local)
                LogicSimplifier localSimplifier = new LogicSimplifier(symbolTable);
                localSimplifier.setPatternCacheEnabled(patternCacheEnabled);
                
                ConstraintMerger localMerger = new ConstraintMerger(symbolTable, localSimplifier);
                
                for (int idx : partition) {
                    reduceShapeConstraints(shapes.get(idx), localMerger);
                }
            }));
        }
        
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Error in parallel reduction: {}", e.getMessage(), e);
            }
        }
        
        executor.shutdown();
    }
    
    /**
     * Apply constraint merging and simplification to a single shape.
     * After processing, FALSE (unsatisfiable) constraints are removed and recorded
     * in the conflict report.
     */
    private void reduceShapeConstraints(NodeShape shape, ConstraintMerger merger) {
        String shapeName = shape.getUri() != null ? shape.getUri() : "<anonymous>";
        List<PropertyConstraint> constraints = new ArrayList<>(shape.getPropertyConstraints());
        
        if (!constraints.isEmpty()) {
            // DEBUG: Log shape being processed with constraint count
            log.info("Processing shape: {} ({} property constraints)", shapeName, constraints.size());
            
            // Save originals BEFORE any processing (for conflict report)
            // Map by path string for lookup after reduction
            Map<String, List<PropertyConstraint>> originalsByPath = new LinkedHashMap<>();
            for (PropertyConstraint pc : constraints) {
                String pathKey = pc.getPath() != null ? pc.getPath().toString() : "<null>";
                originalsByPath.computeIfAbsent(pathKey, k -> new ArrayList<>()).add(pc.copy());
            }
            
            // Step 1: Optimize each constraint (remove redundancies, detect conflicts → FALSE)
            long t1 = System.currentTimeMillis();
            List<PropertyConstraint> optimized = new ArrayList<>();
            for (PropertyConstraint pc : constraints) {
                optimized.add(optimizer.optimize(pc));
            }
            long optimizeTime = System.currentTimeMillis() - t1;
            
            // Step 2: Merge constraints with same path + simplify logical expressions
            long t2 = System.currentTimeMillis();
            List<PropertyConstraint> reduced = merger.reduceConstraints(optimized);
            long mergeTime = System.currentTimeMillis() - t2;
            
            // Log timing info for slower shapes
            if (optimizeTime + mergeTime > STEP_TIMING_THRESHOLD_MS) {
                log.info("  Shape {}: optimize={}ms, merge={}ms, constraints {} -> {}",
                        shapeName, optimizeTime, mergeTime,
                        constraints.size(), reduced.size());
            }
            
            // Step 3: Separate FALSE constraints from normal ones
            List<PropertyConstraint> normalConstraints = new ArrayList<>();
            int falseCount = 0;
            
            for (PropertyConstraint pc : reduced) {
                if (isFalseConstraint(pc)) {
                    falseCount++;
                    // Find original PCs for this path to record in conflict report
                    String pathKey = pc.getPath() != null ? pc.getPath().toString() : "<null>";
                    List<PropertyConstraint> originals = originalsByPath.get(pathKey);
                    
                    String reason = buildConflictReason(pc);
                    
                    if (originals != null && !originals.isEmpty()) {
                        // Record each original PC that contributed to this conflict
                        for (PropertyConstraint originalPC : originals) {
                            conflictReport.addConflict(shape, originalPC, reason);
                        }
                    } else {
                        // Fallback: record the reduced PC itself (shouldn't happen normally)
                        conflictReport.addConflict(shape, pc.copy(), reason);
                    }
                    
                    log.info("  Removed FALSE PropertyShape: path={} ({})", pathKey, reason);
                } else {
                    normalConstraints.add(pc);
                }
            }
            
            shape.clearPropertyConstraints();
            for (PropertyConstraint pc : normalConstraints) {
                shape.addPropertyConstraint(pc);
            }
            
            if (falseCount > 0) {
                log.warn("  Shape {}: removed {} FALSE constraint(s), {} remaining",
                        shapeName, falseCount, normalConstraints.size());
            } else {
                log.info("  Shape {} done: {} -> {} constraints", shapeName, constraints.size(), reduced.size());
            }
        }
        
        // Step 4: Simplify sh:xone alternatives (structural deduplication)
        simplifyXoneConstraints(shape, shapeName);
    }
    
    /**
     * Deduplicate sh:xone alternatives on both PropertyShape and NodeShape levels.
     * 
     * Two-step process per level (matches Algorithm 11 in the pseudocode):
     *   Step a: Intra-branch simplification — parse each branch → optimise → simplify → rebuild
     *   Step b: Structural deduplication — remove equivalent branches via deep blank-node comparison
     * 
     * - PropertyShape level: ConstraintType.XONE stores List<RDFNode> of alternatives
     * - NodeShape level: LogicalConstraint(XONE) stores operands (Resource blank nodes)
     * 
     * Duplicate alternatives are reduced while preserving at least one duplicate pair
     * to express the inherent contradiction (xone with identical branches can never be satisfied).
     */
    @SuppressWarnings("unchecked")
    private void simplifyXoneConstraints(NodeShape shape, String shapeName) {
        if (sourceModel == null) return;
        
        // Lazy-initialise the branch simplifier (reuses existing optimizer + simplifier)
        XoneBranchSimplifier branchSimplifier = new XoneBranchSimplifier(
                sourceModel, simplifier.getSymbolTable(), optimizer, simplifier);
        
        // PropertyShape-level xone
        for (PropertyConstraint pc : shape.getPropertyConstraints()) {
            Object xoneValue = pc.getConstraint(PropertyConstraint.ConstraintType.XONE);
            if (xoneValue instanceof List<?> xoneList && !xoneList.isEmpty()) {
                List<RDFNode> alternatives = (List<RDFNode>) xoneList;
                
                // Step a: Intra-branch simplification (also removes FALSE branches)
                List<RDFNode> simplified = branchSimplifier.simplifyBranches(alternatives);
                int trueCount = branchSimplifier.getTrueBranchCount();
                
                // Step b: Structural deduplication
                if (simplified.size() > 1) {
                    simplified = XorOptimizer.simplifyXoneAlternatives(
                            simplified, shapeName, RDFNodeEquivalence.equivalenceChecker(sourceModel));
                }
                
                // Handle xone edge cases after simplification
                if (simplified.isEmpty()) {
                    // All branches were FALSE → xone is unsatisfiable
                    pc.removeConstraint(PropertyConstraint.ConstraintType.XONE);
                    pc.setLogicalExpression(ConstantExpr.FALSE);
                    log.info("  XONE on {} all branches FALSE → unsatisfiable", pc.getPath());
                } else if (trueCount >= 2) {
                    // 2+ TRUE branches → impossible to satisfy "exactly one"
                    pc.removeConstraint(PropertyConstraint.ConstraintType.XONE);
                    pc.setLogicalExpression(ConstantExpr.FALSE);
                    log.info("  XONE on {} has {} TRUE branches → unsatisfiable", pc.getPath(), trueCount);
                } else if (simplified.size() == 1) {
                    // Single branch: XONE(A) = A → unwrap
                    unwrapSingleXoneBranch(pc, simplified.get(0), branchSimplifier, shapeName);
                } else {
                    // Multiple branches remaining — update
                    pc.setConstraint(PropertyConstraint.ConstraintType.XONE, simplified);
                    if (simplified.size() < alternatives.size()) {
                        log.info("  PropertyShape xone on {}: {} -> {} alternatives (simplify + dedup)",
                                pc.getPath(), alternatives.size(), simplified.size());
                    }
                }
            }
        }
        
        // NodeShape-level xone
        for (LogicalConstraint lc : shape.getLogicalConstraints()) {
            if (lc.getType() == LogicalConstraint.LogicalType.XONE && lc.getOperandCount() >= 1) {
                List<Object> operands = lc.getOperands();
                
                // Step a: Intra-branch simplification (operands are Resources)
                List<RDFNode> asNodes = new ArrayList<>();
                for (Object op : operands) {
                    if (op instanceof RDFNode n) asNodes.add(n);
                    else if (op instanceof Resource r) asNodes.add(r);
                }
                if (!asNodes.isEmpty()) {
                    List<RDFNode> simplified = branchSimplifier.simplifyBranches(asNodes);
                    int trueCount = branchSimplifier.getTrueBranchCount();
                    
                    // Step b: Structural deduplication
                    List<Object> simplifiedObj = new ArrayList<>(simplified);
                    if (simplifiedObj.size() > 1) {
                        simplifiedObj = XorOptimizer.simplifyXoneAlternatives(
                                simplifiedObj, shapeName, RDFNodeEquivalence.operandEquivalenceChecker(sourceModel));
                    }
                    
                    if (simplifiedObj.isEmpty()) {
                        // All FALSE → clear operands (will be removed as empty)
                        lc.replaceOperands(Collections.emptyList());
                        log.info("  NodeShape xone on {} all branches FALSE → unsatisfiable", shapeName);
                    } else if (trueCount >= 2) {
                        lc.replaceOperands(Collections.emptyList());
                        log.info("  NodeShape xone on {} has {} TRUE branches → unsatisfiable", shapeName, trueCount);
                    } else {
                        lc.replaceOperands(simplifiedObj);
                        if (simplifiedObj.size() < operands.size()) {
                            log.info("  NodeShape xone on {}: {} -> {} alternatives (simplify + dedup)",
                                    shapeName, operands.size(), simplifiedObj.size());
                        }
                    }
                }
            }
        }
        
        if (branchSimplifier.getBranchesSimplified() > 0 || branchSimplifier.getFalseBranchesRemoved() > 0) {
            log.info("  Xone branches on {}: {} simplified, {} unchanged, {} FALSE removed",
                    shapeName, branchSimplifier.getBranchesSimplified(), 
                    branchSimplifier.getBranchesUnchanged(), branchSimplifier.getFalseBranchesRemoved());
        }
    }
    
    /**
     * Unwrap a single xone branch: XONE(A) = A.
     * Merges the branch's constraints into the parent PropertyConstraint.
     */
    private void unwrapSingleXoneBranch(PropertyConstraint pc, RDFNode branchNode,
                                         XoneBranchSimplifier branchSimplifier, String shapeName) {
        PropertyConstraint branchPC = branchSimplifier.parseBranch(branchNode);
        if (branchPC == null) {
            // Can't parse — keep as xone([single])
            pc.setConstraint(PropertyConstraint.ConstraintType.XONE, List.of(branchNode));
            return;
        }
        
        // Remove xone from parent
        pc.removeConstraint(PropertyConstraint.ConstraintType.XONE);
        
        // Merge branch constraints into parent
        for (Map.Entry<PropertyConstraint.ConstraintType, Object> entry : branchPC.getConstraints().entrySet()) {
            PropertyConstraint.ConstraintType type = entry.getKey();
            // Skip OR — handled by logical expression
            if (type == PropertyConstraint.ConstraintType.OR) continue;
            pc.setConstraint(type, entry.getValue());
        }
        
        // Merge logical expressions (AND semantics)
        LogicalExpression branchExpr = branchPC.getLogicalExpression();
        if (branchExpr != null) {
            LogicalExpression parentExpr = pc.getLogicalExpression();
            if (parentExpr != null) {
                pc.setLogicalExpression(new AndExpr(List.of(parentExpr, branchExpr)));
            } else {
                pc.setLogicalExpression(branchExpr);
            }
        }
        
        log.info("  XONE({}) unwrapped single branch on {}", pc.getPath(), shapeName);
    }
    
    /**
     * Check if a PropertyConstraint has been simplified to FALSE (unsatisfiable).
     */
    private boolean isFalseConstraint(PropertyConstraint pc) {
        return pc.getLogicalExpression() instanceof ConstantExpr ce && !ce.getValue();
    }
    
    /**
     * Build a human-readable reason for why a constraint became FALSE.
     */
    private String buildConflictReason(PropertyConstraint pc) {
        List<String> reasons = new ArrayList<>();
        
        // Check attribute-level conflicts
        Integer minCount = pc.getMinCount();
        Integer maxCount = pc.getMaxCount();
        if (minCount != null && maxCount != null && minCount > maxCount) {
            reasons.add(String.format("minCount(%d) > maxCount(%d)", minCount, maxCount));
        }
        
        // Check other attribute conflicts
        checker.ConstraintValidator validator = new checker.ConstraintValidator();
        List<checker.ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        for (var issue : issues) {
            if (issue.severity() == checker.ConstraintValidator.IssueSeverity.ERROR) {
                reasons.add(issue.message());
            }
        }
        
        if (reasons.isEmpty()) {
            reasons.add("logical expression simplified to FALSE");
        }
        
        return String.join("; ", reasons);
    }
    
    /**
     * Remove NodeShapes from the ShapeGraph that have no remaining PropertyConstraints
     * and no LogicalConstraints (all their constraints were FALSE).
     * 
     * @return the number of NodeShapes removed
     */
    private int removeEmptyNodeShapes(ShapeGraph shapeGraph) {
        List<String> toRemove = new ArrayList<>();
        
        for (NodeShape shape : shapeGraph.getNodeShapes()) {
            if (shape.getPropertyConstraints().isEmpty() && shape.getLogicalConstraints().isEmpty()
                    && shape.getHasClass() == null && shape.getNodeRefs().isEmpty()) {
                toRemove.add(shape.getUri());
                log.info("Removing empty NodeShape: {} (all constraints were unsatisfiable)", shape.getUri());
            }
        }
        
        for (String uri : toRemove) {
            shapeGraph.removeNodeShape(uri);
        }
        
        return toRemove.size();
    }
    
    /**
     * Estimate workload for a NodeShape based on constraint complexity.
     */
    private int estimateShapeWorkload(NodeShape shape) {
        int totalCost = 0;
        
        for (PropertyConstraint pc : shape.getPropertyConstraints()) {
            LogicalExpression expr = pc.getLogicalExpression();
            if (expr == null) {
                totalCost += 1;
                continue;
            }
            totalCost += countExpressionNodes(expr);
        }
        
        return Math.max(1, totalCost);
    }
    
    /**
     * Count total nodes in expression tree.
     */
    private int countExpressionNodes(LogicalExpression expr) {
        if (expr instanceof SymbolExpr) {
            return 1;
        } else if (expr instanceof NotExpr not) {
            return 1 + countExpressionNodes(not.getArg());
        } else if (expr instanceof AndExpr and) {
            int count = 1;
            for (LogicalExpression arg : and.getArgs()) {
                count += countExpressionNodes(arg);
            }
            return count;
        } else if (expr instanceof OrExpr or) {
            int count = 1;
            for (LogicalExpression arg : or.getArgs()) {
                count += countExpressionNodes(arg);
            }
            return count;
        }
        return 1;
    }
    
    /**
     * Balanced partition using greedy algorithm.
     * Assigns each item to the partition with smallest total weight.
     */
    private List<List<Integer>> balancedPartition(int numItems, int[] weights, int numPartitions) {
        Integer[] indices = new Integer[numItems];
        for (int i = 0; i < numItems; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Integer.compare(weights[b], weights[a]));
        
        List<List<Integer>> partitions = new ArrayList<>();
        int[] partitionWeights = new int[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            partitions.add(new ArrayList<>());
        }
        
        for (int idx : indices) {
            int minPartition = 0;
            for (int i = 1; i < numPartitions; i++) {
                if (partitionWeights[i] < partitionWeights[minPartition]) {
                    minPartition = i;
                }
            }
            partitions.get(minPartition).add(idx);
            partitionWeights[minPartition] += weights[idx];
        }
        
        return partitions;
    }
}
