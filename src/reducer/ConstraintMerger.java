package reducer;

import model.*;
import logic.*;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.logicng.formulas.Formula;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Constraint merger that handles PropertyShape fusion.
 * 
 * Corresponds to Python's ConstraintReducer class functionality:
 * - Groups PropertyConstraints by path
 * - Merges constraints with same path
 * - Handles sh:in intersection
 * - Smart merges minCount/maxCount
 * - Optimizes count constraints in OR expressions
 */
public class ConstraintMerger {
    private static final Logger log = LoggerFactory.getLogger(ConstraintMerger.class);
    
    private final SymbolTable symbolTable;
    private final LogicSimplifier simplifier;
    
    // Smart parallel simplification settings
    private boolean parallelEnabled = false;
    private int parallelism = Runtime.getRuntime().availableProcessors();
    
    public ConstraintMerger(SymbolTable symbolTable, LogicSimplifier simplifier) {
        this.symbolTable = symbolTable;
        this.simplifier = simplifier;
    }
    
    /**
     * Enable or disable parallel simplification.
     */
    public void setParallelEnabled(boolean enabled) {
        this.parallelEnabled = enabled;
    }
    
    /**
     * Set the number of threads for parallel simplification.
     * Default is available processors.
     */
    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }
    
    public boolean isParallelEnabled() {
        return parallelEnabled;
    }
    
    /**
     * Reduce constraints for a NodeShape.
     * Groups by path, merges, simplifies, and filters empty constraints.
     */
    public List<PropertyConstraint> reduceConstraints(List<PropertyConstraint> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return constraints;
        }
        
        // Group by path
        Map<Resource, List<PropertyConstraint>> grouped = groupByPath(constraints);
        
        List<PropertyConstraint> reduced = new ArrayList<>();
        
        // Collect all constraints that need simplification
        List<PropertyConstraint> toOptimize = new ArrayList<>();
        
        for (Map.Entry<Resource, List<PropertyConstraint>> entry : grouped.entrySet()) {
            List<PropertyConstraint> group = entry.getValue();
            
            PropertyConstraint merged = mergeConstraints(group);
            if (merged == null) {
                // Cannot merge - keep all non-empty originals
                for (PropertyConstraint pc : group) {
                    if (!isEmpty(pc)) {
                        toOptimize.add(pc);
                        reduced.add(pc);
                    }
                }
            } else {
                if (!isEmpty(merged)) {
                    toOptimize.add(merged);
                    reduced.add(merged);
                }
            }
        }
        
        // Batch optimize: Use sequential processing by default
        // Parallel processing is available but typically adds overhead for per-shape batches
        // due to ForkJoinPool creation cost and small task sizes.
        // 
        // For better parallelization, consider collecting all constraints across all shapes
        // first, then running a single batch parallel simplification at the ShaclParser level.
        if (parallelEnabled && toOptimize.size() >= 100) {
            // Only use parallel for large batches (e.g., global batch processing)
            optimizeConstraintsParallel(toOptimize);
        } else {
            for (int i = 0; i < toOptimize.size(); i++) {
                PropertyConstraint pc = toOptimize.get(i);
                long pcStart = System.currentTimeMillis();
                optimizeConstraint(pc);
                long pcTime = System.currentTimeMillis() - pcStart;
                if (pcTime > 1000) {
                    LogicalExpression expr = pc.getLogicalExpression();
                    int syms = expr != null ? expr.getFreeSymbols().size() : 0;
                    log.warn("  optimizeConstraint[{}/{}] path={} took {}ms (symbols={})",
                            i + 1, toOptimize.size(), pc.getPath(), pcTime, syms);
                }
            }
        }
        
        return reduced;
    }
    
    /**
     * Group PropertyConstraints by their path.
     */
    private Map<Resource, List<PropertyConstraint>> groupByPath(List<PropertyConstraint> constraints) {
        Map<Resource, List<PropertyConstraint>> groups = new LinkedHashMap<>();
        for (PropertyConstraint pc : constraints) {
            Resource path = pc.getPath();
            groups.computeIfAbsent(path, k -> new ArrayList<>()).add(pc);
        }
        return groups;
    }
    
    /**
     * Merge multiple PropertyConstraints with the same path.
     * Returns null if constraints cannot be merged (conflicts).
     */
    private PropertyConstraint mergeConstraints(List<PropertyConstraint> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }
        
        if (group.size() == 1) {
            return group.get(0);
        }
        
        // Check for conflicts in single-valued properties
        if (hasSingleValuedConflict(group, PropertyConstraint.ConstraintType.DATATYPE)) return null;
        if (hasSingleValuedConflict(group, PropertyConstraint.ConstraintType.NODE_KIND)) return null;
        if (hasSingleValuedConflict(group, PropertyConstraint.ConstraintType.CLASS)) return null;
        if (hasSingleValuedConflict(group, PropertyConstraint.ConstraintType.PATTERN)) return null;
        
        // All checks passed - merge
        PropertyConstraint first = group.get(0);
        PropertyConstraint merged = new PropertyConstraint(first.getUri(), first.getPath());
        
        // Merge in_symbols: intersection
        Set<String> mergedInSymbols = mergeInSymbols(group);
        merged.setInSymbols(mergedInSymbols);
        
        // Merge logical constraints: combine with AND
        LogicalExpression mergedLogical = mergeLogicalConstraints(group);
        merged.setLogicalExpression(mergedLogical);
        
        // Smart merge minCount/maxCount (AND semantics)
        Integer mergedMinCount = smartMergeMinCount(group, "and");
        Integer mergedMaxCount = smartMergeMaxCount(group, "and");

        // Detect unsatisfiable cardinality interval [min,max] with min > max.
        // Mark the merged constraint as FALSE and let ShapeReducer.isFalseConstraint()
        // drive removal — do NOT emit a self-contradictory min/max pair downstream.
        if (mergedMinCount != null && mergedMaxCount != null && mergedMinCount > mergedMaxCount) {
            log.warn("Unsatisfiable constraint: minCount {} > maxCount {} for path {} — marking FALSE",
                    mergedMinCount, mergedMaxCount, first.getPath());
            merged.setLogicalExpression(ConstantExpr.FALSE);
            return merged;
        }
        if (mergedMinCount != null) merged.setMinCount(mergedMinCount);
        if (mergedMaxCount != null) merged.setMaxCount(mergedMaxCount);
        
        // Merge other single-valued properties (take first non-null)
        merged.setDatatype(getFirstNonNull(group, PropertyConstraint.ConstraintType.DATATYPE));
        merged.setNodeKind(getFirstNonNull(group, PropertyConstraint.ConstraintType.NODE_KIND));
        merged.setConstraint(PropertyConstraint.ConstraintType.CLASS, 
                getFirstNonNull(group, PropertyConstraint.ConstraintType.CLASS));
        merged.setConstraint(PropertyConstraint.ConstraintType.PATTERN,
                getFirstNonNull(group, PropertyConstraint.ConstraintType.PATTERN));
        
        // Merge sh:in values (intersection)
        List<RDFNode> mergedInValues = mergeInValues(group);
        if (mergedInValues != null) {
            merged.setConstraint(PropertyConstraint.ConstraintType.IN, mergedInValues);
        }
        
        // Copy other constraints from first
        copyOtherConstraints(first, merged);
        
        return merged;
    }
    
    /**
     * Check if there's a conflict in single-valued constraint.
     */
    private boolean hasSingleValuedConflict(List<PropertyConstraint> group, 
                                            PropertyConstraint.ConstraintType type) {
        Set<Object> values = new HashSet<>();
        for (PropertyConstraint pc : group) {
            Object val = pc.getConstraint(type);
            if (val != null) {
                values.add(val);
            }
        }
        return values.size() > 1;
    }
    
    /**
     * Get first non-null value for a constraint type.
     */
    @SuppressWarnings("unchecked")
    private <T> T getFirstNonNull(List<PropertyConstraint> group, 
                                   PropertyConstraint.ConstraintType type) {
        for (PropertyConstraint pc : group) {
            Object val = pc.getConstraint(type);
            if (val != null) {
                return (T) val;
            }
        }
        return null;
    }
    
    /**
     * Merge in_symbols using intersection.
     */
    private Set<String> mergeInSymbols(List<PropertyConstraint> group) {
        List<Set<String>> symbolSets = new ArrayList<>();
        for (PropertyConstraint pc : group) {
            Set<String> syms = pc.getInSymbols();
            if (syms != null && !syms.isEmpty()) {
                symbolSets.add(syms);
            }
        }
        
        if (symbolSets.isEmpty()) {
            return new HashSet<>();
        }
        
        // Intersection of all sets
        Set<String> result = new HashSet<>(symbolSets.get(0));
        for (int i = 1; i < symbolSets.size(); i++) {
            result.retainAll(symbolSets.get(i));
        }
        
        return result;
    }
    
    /**
     * Merge sh:in values using intersection.
     */
    @SuppressWarnings("unchecked")
    private List<RDFNode> mergeInValues(List<PropertyConstraint> group) {
        List<List<RDFNode>> inLists = new ArrayList<>();
        
        for (PropertyConstraint pc : group) {
            Object inVal = pc.getConstraint(PropertyConstraint.ConstraintType.IN);
            if (inVal instanceof List) {
                inLists.add((List<RDFNode>) inVal);
            }
        }
        
        if (inLists.isEmpty()) {
            return null;
        }
        
        // Intersection of all lists
        Set<RDFNode> result = new HashSet<>(inLists.get(0));
        for (int i = 1; i < inLists.size(); i++) {
            result.retainAll(inLists.get(i));
        }
        
        return new ArrayList<>(result);
    }
    
    /**
     * Merge logical constraints using AND.
     */
    private LogicalExpression mergeLogicalConstraints(List<PropertyConstraint> group) {
        List<LogicalExpression> exprs = new ArrayList<>();
        for (PropertyConstraint pc : group) {
            LogicalExpression expr = pc.getLogicalExpression();
            if (expr != null) {
                exprs.add(expr);
            }
        }
        
        if (exprs.isEmpty()) {
            return null;
        }
        
        if (exprs.size() == 1) {
            return exprs.get(0);
        }
        
        return new AndExpr(exprs);
    }
    
    /**
     * Smart merge minCount values.
     * AND semantics: take max (≥2 AND ≥3 → ≥3)
     * OR semantics: take min (≥2 OR ≥3 → ≥2)
     */
    private Integer smartMergeMinCount(List<PropertyConstraint> group, String mode) {
        List<Integer> values = new ArrayList<>();
        for (PropertyConstraint pc : group) {
            Integer val = pc.getMinCount();
            // minCount=0 is a no-op (SHACL default); exclude it so it cannot
            // "contaminate" a merge result when the other operand has no minCount.
            if (val != null && val > 0) {
                values.add(val);
            }
        }
        
        if (values.isEmpty()) {
            return null;
        }
        
        if ("and".equals(mode)) {
            return Collections.max(values);  // More restrictive
        } else {
            return Collections.min(values);  // Less restrictive
        }
    }
    
    /**
     * Smart merge maxCount values.
     * AND semantics: take min (≤5 AND ≤3 → ≤3)
     * OR semantics: take max (≤5 OR ≤3 → ≤5)
     */
    /**
     * Recursively replace symbols for always-TRUE constraints with ConstantExpr.TRUE.
     * Propagates the replacement through AND / OR / NOT using standard boolean rules:
     *   AND(..., TRUE, ...) = AND of remaining args
     *   OR(..., TRUE, ...)  = TRUE
     *   NOT(TRUE)           = FALSE
     */
    private LogicalExpression replaceAlwaysTrueSymbols(LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            return isAlwaysTrueSymbol(sym) ? ConstantExpr.TRUE : expr;
        }
        if (expr instanceof AndExpr and) {
            List<LogicalExpression> kept = new ArrayList<>();
            for (LogicalExpression arg : and.getArgs()) {
                LogicalExpression r = replaceAlwaysTrueSymbols(arg);
                if (r instanceof ConstantExpr ce) {
                    if (!ce.getValue()) return ConstantExpr.FALSE; // AND(... FALSE ...) = FALSE
                    // TRUE in AND = skip (neutral element)
                } else {
                    kept.add(r);
                }
            }
            if (kept.isEmpty()) return ConstantExpr.TRUE;
            if (kept.size() == 1) return kept.get(0);
            return LogicalExpression.and(kept);
        }
        if (expr instanceof OrExpr or) {
            List<LogicalExpression> kept = new ArrayList<>();
            for (LogicalExpression arg : or.getArgs()) {
                LogicalExpression r = replaceAlwaysTrueSymbols(arg);
                if (r instanceof ConstantExpr ce) {
                    if (ce.getValue()) return ConstantExpr.TRUE; // OR(... TRUE ...) = TRUE
                    // FALSE in OR = skip
                } else {
                    kept.add(r);
                }
            }
            if (kept.isEmpty()) return ConstantExpr.FALSE;
            if (kept.size() == 1) return kept.get(0);
            return LogicalExpression.or(kept);
        }
        if (expr instanceof NotExpr not) {
            LogicalExpression r = replaceAlwaysTrueSymbols(not.getArg());
            if (r instanceof ConstantExpr ce) {
                return ce.getValue() ? ConstantExpr.FALSE : ConstantExpr.TRUE;
            }
            if (r == not.getArg()) return expr;
            return LogicalExpression.not(r);
        }
        return expr;
    }

    /** Returns true if the symbol represents a constraint that is always satisfied. */
    private boolean isAlwaysTrueSymbol(SymbolExpr sym) {
        SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
        if (info == null) return false;
        String type = info.constraintType();
        if (!"sh:minCount".equals(type) && !"sh:minLength".equals(type)) return false;
        RDFNode val = info.constraintValue();
        if (val == null || !val.isLiteral()) return false;
        try { return val.asLiteral().getInt() == 0; } catch (Exception e) { return false; }
    }

    private Integer smartMergeMaxCount(List<PropertyConstraint> group, String mode) {
        List<Integer> values = new ArrayList<>();
        for (PropertyConstraint pc : group) {
            Integer val = pc.getMaxCount();
            if (val != null) {
                values.add(val);
            }
        }
        
        if (values.isEmpty()) {
            return null;
        }
        
        if ("and".equals(mode)) {
            return Collections.min(values);  // More restrictive
        } else {
            return Collections.max(values);  // Less restrictive
        }
    }
    
    /**
     * Copy other constraints from source to target.
     */
    private void copyOtherConstraints(PropertyConstraint source, PropertyConstraint target) {
        // Copy constraints that aren't merged specially
        for (PropertyConstraint.ConstraintType type : PropertyConstraint.ConstraintType.values()) {
            switch (type) {
                case MIN_COUNT, MAX_COUNT, DATATYPE, NODE_KIND, CLASS, PATTERN, IN:
                    // Already handled
                    break;
                default:
                    Object val = source.getConstraint(type);
                    if (val != null && target.getConstraint(type) == null) {
                        target.setConstraint(type, val);
                    }
            }
        }
    }
    
    private int optimizeCallCount = 0;
    private int skippedSymbolCount = 0;
    private int skippedNotSymbolCount = 0;
    private int skippedAndOfSingleValuedCount = 0;
    private int actualSimplifyCount = 0;
    
    /**
     * Optimize a constraint by simplifying its logical expression.
     */
    private void optimizeConstraint(PropertyConstraint pc) {
        optimizeCallCount++;
        // Remove attribute-level redundancies that ConstraintOptimizer.removeRedundancies()
        // would normally handle — needed because reduceConstraints() calls this method
        // directly instead of going through optimizer.optimize().
        if (pc.getMinCount() != null && pc.getMinCount() == 0) {
            pc.setMinCount(null);
            pc.getConstraints().remove(PropertyConstraint.ConstraintType.MIN_COUNT);
        }
        if (pc.getConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH) instanceof Integer ml && ml == 0) {
            pc.getConstraints().remove(PropertyConstraint.ConstraintType.MIN_LENGTH);
        }
        LogicalExpression expr = pc.getLogicalExpression();
        if (expr == null) {
            return;
        }

        // Replace symbols for always-TRUE constraints (minCount=0, minLength=0) with
        // ConstantExpr.TRUE before boolean simplification.  These symbols legitimately
        // appear in merged expressions because individual PCs carried them from the
        // parser even after optimizer.optimize() removed the corresponding attribute.
        LogicalExpression substituted = replaceAlwaysTrueSymbols(expr);
        if (substituted != expr) {
            if (substituted instanceof ConstantExpr ce && ce.getValue()) {
                pc.setLogicalExpression(null);
                return;
            }
            if (substituted instanceof ConstantExpr ce && !ce.getValue()) {
                pc.setLogicalExpression(substituted);
                return;
            }
            expr = substituted;
            pc.setLogicalExpression(expr);
        }

        // Skip constant expressions (TRUE/FALSE) - they are already simplified
        // These are typically set by ConstraintOptimizer for unsatisfiable constraints
        if (expr instanceof ConstantExpr) {
            return;
        }
        
        // Skip atomic expressions - they don't need simplification
        // This matches Python behavior where sh:nodeKind, sh:datatype are handled as attributes
        // For Java, we still store them as logical expressions but skip simplification
        if (expr instanceof SymbolExpr) {
            skippedSymbolCount++;
            return;
        }
        
        // Skip Not(Symbol) - already in simplest form
        if (expr instanceof NotExpr notExpr && notExpr.getArg() instanceof SymbolExpr) {
            skippedNotSymbolCount++;
            return;
        }
        
        // Skip And expressions that only contain single-valued constraint symbols
        // (nodeKind, datatype, class, pattern) - they don't need boolean simplification
        if (expr instanceof AndExpr andExpr && isAllSingleValuedConstraints(andExpr)) {
            skippedAndOfSingleValuedCount++;
            return;
        }
        
        actualSimplifyCount++;
        
        // Debug: Log property path and expression size for complex expressions
        int symbolCount = expr.getFreeSymbols().size();
        String pathInfo = pc.getPath() != null ? pc.getPath().toString() : "<unknown path>";
        if (symbolCount > 100) {
            log.info("  Simplifying property: {} (symbols={})", pathInfo, symbolCount);
        }
        
        // Record original branch order before simplification
        Map<String, Integer> originalBranchOrder = collectBranchOrder(expr);
        
        long t0 = System.currentTimeMillis();
        
        // Optimize count constraints in OR expressions
        expr = optimizeCountConstraintsInOr(expr);
        
        // Simplify with LogicNG
        LogicalExpression simplified = simplifier.simplify(expr);
        
        long elapsed = System.currentTimeMillis() - t0;
        if (elapsed > 1000) {
            log.info("  Property {} took {}ms to simplify", pathInfo, elapsed);
        }
        
        if (simplified instanceof ConstantExpr ce && ce.getValue()) {
            pc.setLogicalExpression(null);
        } else {
            // Restore original branch order in OR expressions
            simplified = restoreBranchOrder(simplified, originalBranchOrder);
            pc.setLogicalExpression(simplified);
        }
    }
    
    /**
     * Collect original position of each OR branch.
     * Processes smallest ORs first so that when OR-1 ⊆ OR-2,
     * OR-1's branch positions take priority (OR-1 survives absorption).
     * Uses a global counter so positions never collide across ORs.
     */
    private Map<String, Integer> collectBranchOrder(LogicalExpression expr) {
        // 1. Collect all OR expressions in the tree
        List<OrExpr> allOrs = new ArrayList<>();
        collectAllOrs(expr, allOrs);
        
        // 2. Sort by size (smallest first) — smallest OR most likely survives
        allOrs.sort(Comparator.comparingInt(o -> o.getArgs().size()));
        
        // 3. Assign positions: smallest OR first, global counter
        Map<String, Integer> order = new HashMap<>();
        int seq = 0;
        for (OrExpr or : allOrs) {
            for (LogicalExpression arg : or.getArgs()) {
                String key = branchKey(arg);
                if (!order.containsKey(key)) {
                    order.put(key, seq++);
                }
            }
        }
        return order;
    }
    
    private void collectAllOrs(LogicalExpression expr, List<OrExpr> result) {
        if (expr instanceof OrExpr or) {
            result.add(or);
        }
        if (expr instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) {
                collectAllOrs(arg, result);
            }
        }
    }
    
    /**
     * Restore original branch order in OR expressions after simplification.
     * Branches that existed in the original get their original position;
     * new branches (from simplification) go to the end.
     */
    private LogicalExpression restoreBranchOrder(LogicalExpression expr, Map<String, Integer> originalOrder) {
        if (originalOrder.isEmpty()) return expr;
        
        if (expr instanceof OrExpr or) {
            List<LogicalExpression> args = new ArrayList<>(or.getArgs());
            args.sort(Comparator.comparingInt(a -> originalOrder.getOrDefault(branchKey(a), Integer.MAX_VALUE)));
            return new OrExpr(args);
        }
        if (expr instanceof AndExpr and) {
            List<LogicalExpression> newArgs = new ArrayList<>();
            boolean changed = false;
            for (LogicalExpression arg : and.getArgs()) {
                LogicalExpression restored = restoreBranchOrder(arg, originalOrder);
                newArgs.add(restored);
                if (restored != arg) changed = true;
            }
            return changed ? new AndExpr(newArgs) : expr;
        }
        return expr;
    }
    
    /**
     * Create a canonical key for a branch based on its sorted symbol names.
     * This key is stable across reorderings of the same logical content.
     */
    private String branchKey(LogicalExpression expr) {
        return expr.toString();
    }
    
    /**
     * Smart parallel optimization of multiple constraints.
     * Uses workload-balanced task distribution based on expression complexity.
     */
    private void optimizeConstraintsParallel(List<PropertyConstraint> constraints) {
        log.debug("Smart parallel optimize: {} constraints with {} threads", 
                  constraints.size(), parallelism);
        
        // Step 1: Pre-process and filter - collect expressions that actually need simplification
        Map<PropertyConstraint, LogicalExpression> toSimplify = new LinkedHashMap<>();
        
        for (PropertyConstraint pc : constraints) {
            optimizeCallCount++;
            LogicalExpression expr = pc.getLogicalExpression();
            if (expr == null) {
                continue;
            }
            
            // Skip atomic expressions
            if (expr instanceof SymbolExpr) {
                skippedSymbolCount++;
                continue;
            }
            
            // Skip Not(Symbol)
            if (expr instanceof NotExpr notExpr && notExpr.getArg() instanceof SymbolExpr) {
                skippedNotSymbolCount++;
                continue;
            }
            
            // Skip And of single-valued constraints
            if (expr instanceof AndExpr andExpr && isAllSingleValuedConstraints(andExpr)) {
                skippedAndOfSingleValuedCount++;
                continue;
            }
            
            actualSimplifyCount++;
            // Pre-optimize count constraints (cheap operation, can be done here)
            expr = optimizeCountConstraintsInOr(expr);
            toSimplify.put(pc, expr);
        }
        
        if (toSimplify.isEmpty()) {
            return;
        }
        
        // Step 2: Convert to formulas for batch processing
        Map<PropertyConstraint, Formula> formulas = new LinkedHashMap<>();
        for (Map.Entry<PropertyConstraint, LogicalExpression> entry : toSimplify.entrySet()) {
            Formula formula = simplifier.toFormula(entry.getValue());
            formulas.put(entry.getKey(), formula);
        }
        
        // Step 3: Batch parallel simplify with workload balancing
        Map<PropertyConstraint, Formula> simplified = 
            simplifier.simplifyBatchParallel(formulas, parallelism);
        
        // Step 4: Convert results back and update constraints
        for (Map.Entry<PropertyConstraint, Formula> entry : simplified.entrySet()) {
            LogicalExpression result = simplifier.fromFormula(entry.getValue());
            if (result instanceof ConstantExpr ce && ce.getValue()) {
                entry.getKey().setLogicalExpression(null);
            } else {
                entry.getKey().setLogicalExpression(result);
            }
        }
        
        log.debug("Parallel optimization complete: {} expressions simplified", toSimplify.size());
    }
    
    /**
     * Check if an AndExpr contains only single-valued constraint symbols.
     * These don't benefit from boolean simplification.
     */
    private boolean isAllSingleValuedConstraints(AndExpr andExpr) {
        for (LogicalExpression arg : andExpr.getArgs()) {
            if (!(arg instanceof SymbolExpr sym)) {
                return false;
            }
            SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
            if (info == null) {
                return false;
            }
            // Single-valued constraints that don't need simplification
            String type = info.constraintType();
            if (!isSingleValuedConstraint(type)) {
                return false;
            }
        }
        return true;
    }
    
    private boolean isSingleValuedConstraint(String constraintType) {
        return switch (constraintType) {
            case "sh:nodeKind", "sh:datatype", "sh:class", "sh:pattern",
                 "sh:minLength", "sh:maxLength", "sh:minInclusive", "sh:maxInclusive",
                 "sh:minExclusive", "sh:maxExclusive", "sh:languageIn", "sh:uniqueLang" -> true;
            default -> false;
        };
    }
    
    public void printOptimizeStats() {
        System.out.println("ConstraintMerger optimize stats:");
        System.out.println("  Total optimizeConstraint calls: " + optimizeCallCount);
        System.out.println("  Skipped (SymbolExpr): " + skippedSymbolCount);
        System.out.println("  Skipped (Not(Symbol)): " + skippedNotSymbolCount);
        System.out.println("  Skipped (And of single-valued): " + skippedAndOfSingleValuedCount);
        System.out.println("  Actual simplify calls: " + actualSimplifyCount);
    }
    
    /**
     * Interval-aware merge of cardinality constraints inside an OR.
     *
     * A PropertyConstraint has a single sh:path, so every minCount/maxCount symbol
     * within its logicalExpression refers to that same path's cardinality. Each
     * disjunct is decomposed into (rest, [l, u]) where rest is the conjunction of
     * non-count atoms and [l, u] folds all minCount/maxCount atoms in that disjunct
     * (l = max of minCount atoms, u = min of maxCount atoms; defaults 0 / +∞).
     *
     * Disjuncts with identical rest are bucketed together; within a bucket the
     * intervals are unioned via touching/overlap (integer counts: gap of 1 is
     * touching). Only when a bucket's union collapses to a single interval do we
     * rewrite — otherwise the original disjuncts are preserved so common-factor
     * extraction (e.g., distributing rest) can run later. This avoids the unsound
     * collapse of e.g. (X ∧ c≥2 ∧ c≤3) ∨ (X ∧ c≥5 ∧ c≤6) into (X ∧ c≥2 ∧ c≤6),
     * which would wrongly admit c=4.
     */
    // Package-private for unit testing the interval-aware OR merge in isolation.
    LogicalExpression optimizeCountConstraintsInOr(LogicalExpression expr) {
        if (!(expr instanceof OrExpr or)) {
            return expr;
        }
        List<LogicalExpression> disjuncts = or.getArgs();
        if (disjuncts.size() < 2) return expr;

        // Step 1 — decompose each disjunct
        List<DecomposedDisjunct> decomposed = new ArrayList<>(disjuncts.size());
        boolean anyHasCount = false;
        for (LogicalExpression d : disjuncts) {
            DecomposedDisjunct dd = decomposeDisjunct(d);
            if (dd == null) return expr; // unsat (l > u inside one disjunct)
            if (dd.minCount > 0 || dd.maxCount != Integer.MAX_VALUE) anyHasCount = true;
            decomposed.add(dd);
        }
        if (!anyHasCount) return expr;

        // Step 2 — bucket by canonical rest key (insertion order preserved)
        Map<List<String>, List<Integer>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < decomposed.size(); i++) {
            buckets.computeIfAbsent(decomposed.get(i).restKey, k -> new ArrayList<>()).add(i);
        }

        // Step 3+4 — interval transitive union per bucket; rewrite if collapsed
        List<LogicalExpression> result = new ArrayList<>();
        boolean changed = false;
        for (List<Integer> indices : buckets.values()) {
            if (indices.size() == 1) {
                result.add(disjuncts.get(indices.get(0)));
                continue;
            }
            DecomposedDisjunct sample = decomposed.get(indices.get(0));
            List<int[]> intervals = new ArrayList<>(indices.size());
            for (int idx : indices) {
                DecomposedDisjunct dd = decomposed.get(idx);
                intervals.add(new int[]{dd.minCount, dd.maxCount});
            }
            intervals.sort(Comparator.comparingInt(a -> a[0]));
            List<int[]> merged = new ArrayList<>();
            int[] cur = intervals.get(0).clone();
            for (int i = 1; i < intervals.size(); i++) {
                int[] nx = intervals.get(i);
                // Touching/overlap on integers: nx.l <= cur.u + 1 (cur.u == +∞ always touches)
                if (cur[1] == Integer.MAX_VALUE || (long) nx[0] <= (long) cur[1] + 1L) {
                    cur[1] = Math.max(cur[1], nx[1]);
                } else {
                    merged.add(cur);
                    cur = nx.clone();
                }
            }
            merged.add(cur);

            if (merged.size() == intervals.size()) {
                // No collapse — leave originals untouched (preserves disjuncts so
                // common-factor extraction can still split them).
                for (int idx : indices) result.add(disjuncts.get(idx));
            } else {
                changed = true;
                for (int[] iv : merged) {
                    result.add(buildCountDisjunct(sample.restArgs, iv[0], iv[1]));
                }
            }
        }

        if (!changed) return expr;
        if (result.isEmpty()) return ConstantExpr.FALSE;
        if (result.size() == 1) return result.get(0);
        return new OrExpr(result);
    }

    /** Per-disjunct decomposition into (rest, [l, u]). */
    private static final class DecomposedDisjunct {
        final List<LogicalExpression> restArgs;
        final List<String> restKey;
        final int minCount;            // 0 means "no lower bound" (SHACL default)
        final int maxCount;            // Integer.MAX_VALUE means "no upper bound"
        DecomposedDisjunct(List<LogicalExpression> rest, List<String> key, int min, int max) {
            this.restArgs = rest; this.restKey = key; this.minCount = min; this.maxCount = max;
        }
    }

    /**
     * Decompose one OR-disjunct. Returns null if its own min > max (locally unsat),
     * which the caller treats as "leave the OR alone" — the disjunct will become
     * FALSE during boolean simplification anyway.
     */
    private DecomposedDisjunct decomposeDisjunct(LogicalExpression d) {
        List<LogicalExpression> conjuncts;
        if (d instanceof AndExpr a) {
            conjuncts = a.getArgs();
        } else {
            conjuncts = Collections.singletonList(d);
        }
        int lo = 0;
        int hi = Integer.MAX_VALUE;
        List<LogicalExpression> rest = new ArrayList<>(conjuncts.size());
        for (LogicalExpression c : conjuncts) {
            Integer v = countSymbolValue(c, "sh:minCount");
            if (v != null) { if (v > lo) lo = v; continue; }
            v = countSymbolValue(c, "sh:maxCount");
            if (v != null) { if (v < hi) hi = v; continue; }
            rest.add(c);
        }
        if (lo > hi) return null;
        List<String> key = new ArrayList<>(rest.size());
        for (LogicalExpression r : rest) key.add(r.toString());
        Collections.sort(key);
        return new DecomposedDisjunct(rest, key, lo, hi);
    }

    /** Returns the int literal value if {@code c} is a SymbolExpr for the given count type, else null. */
    private Integer countSymbolValue(LogicalExpression c, String type) {
        if (!(c instanceof SymbolExpr sym)) return null;
        SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
        if (info == null || !type.equals(info.constraintType())) return null;
        RDFNode v = info.constraintValue();
        if (v == null || !v.isLiteral()) return null;
        try { return v.asLiteral().getInt(); } catch (Exception e) { return null; }
    }

    /** Reassemble (rest ∧ minSym(l) ∧ maxSym(u)), omitting trivial bounds. */
    private LogicalExpression buildCountDisjunct(List<LogicalExpression> restArgs, int l, int u) {
        List<LogicalExpression> conjuncts = new ArrayList<>(restArgs.size() + 2);
        conjuncts.addAll(restArgs);
        if (l > 0) {
            conjuncts.add(symbolTable.createSymbolExpr("sh:minCount",
                    ResourceFactory.createTypedLiteral(String.valueOf(l), XSDDatatype.XSDinteger)));
        }
        if (u != Integer.MAX_VALUE) {
            conjuncts.add(symbolTable.createSymbolExpr("sh:maxCount",
                    ResourceFactory.createTypedLiteral(String.valueOf(u), XSDDatatype.XSDinteger)));
        }
        if (conjuncts.isEmpty()) return ConstantExpr.TRUE;
        if (conjuncts.size() == 1) return conjuncts.get(0);
        return new AndExpr(conjuncts);
    }
    
    /**
     * Check if a constraint is empty.
     */
    private boolean isEmpty(PropertyConstraint pc) {
        // Has in_symbols
        if (pc.getInSymbols() != null && !pc.getInSymbols().isEmpty()) {
            return false;
        }
        
        // Has logical expression
        if (pc.getLogicalExpression() != null) {
            return false;
        }
        
        // Has any constraints
        for (PropertyConstraint.ConstraintType type : PropertyConstraint.ConstraintType.values()) {
            if (pc.getConstraint(type) != null) {
                return false;
            }
        }
        
        return true;
    }
}
