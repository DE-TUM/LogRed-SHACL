package logic;

import org.logicng.formulas.*;
import org.logicng.transformations.simplification.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * High-performance logical expression simplifier using LogicNG.
 * 
 * Features:
 * - Expression caching to avoid redundant simplification
 * - Parallel processing support for multiple shapes
 * - Subsumption-based optimization (similar to Python's PySAT approach)
 * - Automatic strategy selection based on expression complexity
 * - Pattern-based caching for improved hit rate
 * 
 * Similar to Python's reducer_pysat.py with PySAT + SymPy.
 */
public class LogicSimplifier {
    private static final Logger log = LoggerFactory.getLogger(LogicSimplifier.class);
    
    // Threshold for using advanced simplification (similar to Python's 8 symbol threshold)
    private static final int COMPLEXITY_THRESHOLD = 1000;
    
    // Maximum symbols for AdvancedSimplifier before switching to recursive DNF.
    // Empirical data from Shape100_wiki (symbols → time):
    //   200 symbols → ~2s, 300 symbols → ~5s, 460 symbols → ~18s, 500+ → unpredictable
    // Beyond 500 symbols, AdvancedSimplifier's exponential nature becomes unacceptable.
    private static final int LOGICNG_MAX_SYMBOLS = 5000;
    
    // LogicNG formula factory (thread-safe)
    private final FormulaFactory factory;
    
    // Variable cache for symbol mapping
    private final Map<String, Variable> varCache;
    
    // Pattern-based cache for structural matching (98%+ hit rate)
    private final PatternCache patternCache;
    
    // Cache statistics
    private final AtomicLong patternCacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    // Whether pattern caching is enabled (structure-based caching)
    // Pattern cache provides 98% hit rate with much smaller memory footprint
    private boolean patternCacheEnabled = true;
    
    // Symbol table reference
    private final SymbolTable symbolTable;
    
    public LogicSimplifier(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
        this.factory = new FormulaFactory();
        this.varCache = new ConcurrentHashMap<>();
        this.patternCache = new PatternCache();
    }
    
    /**
     * Enable or disable pattern-based caching.
     * Pattern caching provides better hit rates for expressions with same structure.
     */
    public LogicSimplifier setPatternCacheEnabled(boolean enabled) {
        this.patternCacheEnabled = enabled;
        return this;
    }
    
    /**
     * Simplify a logical expression using pattern-based caching.
     * Pattern cache normalizes expression structure for better hit rates (98%+).
     */
    public LogicalExpression simplify(LogicalExpression expr) {
        if (expr == null) return null;
        
        // Skip atomic expressions - no simplification needed
        if (expr instanceof SymbolExpr) {
            return expr;
        }
        
        // Skip Not(Symbol) - already in simplest form
        if (expr instanceof NotExpr notExpr && notExpr.getArg() instanceof SymbolExpr) {
            return expr;
        }
        
        // Pattern cache lookup
        if (patternCacheEnabled) {
            LogicalExpression patternResult = tryPatternCache(expr);
            if (patternResult != null) {
                patternCacheHits.incrementAndGet();
                return patternResult;
            }
        }
        
        cacheMisses.incrementAndGet();
        
        // Simplify
        LogicalExpression result = simplifyInternal(expr);
        
        // Cache the result
        if (patternCacheEnabled && result != null) {
            cachePattern(expr, result);
        }
        
        return result;
    }
    
    /**
     * Try to find a cached pattern that matches this expression's structure.
     */
    private LogicalExpression tryPatternCache(LogicalExpression expr) {
        PatternCache.ExpressionPattern pattern = patternCache.extractPattern(expr);
        PatternCache.PatternResult cached = patternCache.getCached(pattern.getPatternString());
        
        if (cached != null) {
            // Apply the symbol mapping to get the concrete result
            return patternCache.applyMapping(
                cached.getSimplifiedPattern(), 
                pattern.getPlaceholderToSymbol()
            );
        }
        return null;
    }
    
    /**
     * Cache the pattern for this expression and its simplified result.
     */
    private void cachePattern(LogicalExpression original, LogicalExpression simplified) {
        PatternCache.ExpressionPattern pattern = patternCache.extractPattern(original);
        
        // Convert simplified expression to pattern form
        LogicalExpression simplifiedPattern = patternCache.toPatternExpression(
            simplified, 
            pattern.getSymbolToPlaceholder()
        );
        
        boolean changed = !original.toString().equals(simplified.toString());
        patternCache.cache(pattern.getPatternString(), simplifiedPattern, changed);
    }
    
    /**
     * Internal simplification logic.
     */
    private LogicalExpression simplifyInternal(LogicalExpression expr) {
        if (expr == null) return null;
        
        try {
            // First recursively simplify sub-expressions
            long t0 = System.currentTimeMillis();
            expr = recursiveSimplify(expr);
            long recursiveTime = System.currentTimeMillis() - t0;
            
            // Get symbol count to decide strategy
            int symbolCount = expr.getFreeSymbols().size();
            int nodeCount = countNodes(expr);
            
            if (recursiveTime > 500) {
                log.warn("  recursiveSimplify took {}ms (symbols={}, nodes={})",
                        recursiveTime, symbolCount, nodeCount);
            }
            
            long t1 = System.currentTimeMillis();
            LogicalExpression result;
            String strategy;
            
            if (symbolCount <= COMPLEXITY_THRESHOLD) {
                strategy = "LogicNG-direct";
                result = simplifyWithLogicNG(expr);
            } else {
                strategy = "subsumption+LogicNG";
                result = simplifyLargeExpression(expr);
            }
            
            long simplifyTime = System.currentTimeMillis() - t1;
            if (simplifyTime > 1000) {
                log.warn("  {} took {}ms (symbols={}, nodes={})",
                        strategy, simplifyTime, symbolCount, nodeCount);
            } else if (simplifyTime > 100) {
                log.info("  {} took {}ms (symbols={}, nodes={})",
                        strategy, simplifyTime, symbolCount, nodeCount);
            }
            
            return result;
            
        } catch (Exception e) {
            log.warn("Simplification failed, returning original: {}", e.getMessage());
            return expr;
        }
    }
    
    private int countNodes(LogicalExpression expr) {
        if (expr instanceof SymbolExpr) return 1;
        if (expr instanceof NotExpr not) return 1 + countNodes(not.getArg());
        if (expr instanceof AndExpr and) {
            int c = 1; for (LogicalExpression a : and.getArgs()) c += countNodes(a); return c;
        }
        if (expr instanceof OrExpr or) {
            int c = 1; for (LogicalExpression a : or.getArgs()) c += countNodes(a); return c;
        }
        return 1;
    }
    
    /**
     * Recursively simplify sub-expressions while preserving XOR patterns.
     */
    private LogicalExpression recursiveSimplify(LogicalExpression expr) {
        if (expr == null) return null;
        
        // Base case: atomic expression
        if (expr instanceof SymbolExpr) {
            return expr;
        }
        
        // Check if this is XOR pattern - preserve it
        if (isXorPattern(expr)) {
            return expr;
        }
        
        // Recursively simplify arguments
        if (expr instanceof OrExpr or) {
            List<LogicalExpression> simplified = new ArrayList<>();
            for (LogicalExpression arg : or.getArgs()) {
                simplified.add(recursiveSimplify(arg));
            }
            LogicalExpression result = simplified.size() == 1 ? simplified.get(0) : new OrExpr(simplified);
            
            // Check again after simplification
            if (isXorPattern(result)) {
                return result;
            }
            return result;
            
        } else if (expr instanceof AndExpr and) {
            // For AND: preserve embedded XOR patterns
            List<LogicalExpression> xorParts = new ArrayList<>();
            List<LogicalExpression> otherParts = new ArrayList<>();
            
            for (LogicalExpression arg : and.getArgs()) {
                LogicalExpression simplified = recursiveSimplify(arg);
                if (isXorPattern(simplified)) {
                    xorParts.add(simplified);
                } else {
                    otherParts.add(simplified);
                }
            }
            
            if (xorParts.isEmpty()) {
                return otherParts.size() == 1 ? otherParts.get(0) : new AndExpr(otherParts);
            }
            
            // Rebuild with XOR patterns preserved
            List<LogicalExpression> result = new ArrayList<>();
            if (!otherParts.isEmpty()) {
                result.add(otherParts.size() == 1 ? otherParts.get(0) : new AndExpr(otherParts));
            }
            result.addAll(xorParts);
            
            return result.size() == 1 ? result.get(0) : new AndExpr(result);
            
        } else if (expr instanceof NotExpr not) {
            return new NotExpr(recursiveSimplify(not.getArg()));
        }
        
        return expr;
    }
    
    /**
     * Check if expression matches XOR pattern: (A & ~B) | (B & ~A) | ...
     * Delegates to XorOptimizer (canonical implementation).
     */
    private boolean isXorPattern(LogicalExpression expr) {
        return reducer.XorOptimizer.isXorPattern(expr);
    }
    
    /**
     * Simplify using LogicNG's AdvancedSimplifier with timeout.
     * Based on empirical data from Shape100_wiki:
     *   - ≤200 symbols: always completes within a few seconds
     *   - 200~500 symbols: may take 5~20 seconds
     *   - >500 symbols: can be exponential, needs timeout
     *   - 10000+ symbols: effectively infinite
     * 
     * When timeout occurs, falls back to recursive DNF simplification
     * (Python's reducer_or approach).
     */
    private LogicalExpression simplifyWithLogicNG(LogicalExpression expr) {
        int symbolCount = expr.getFreeSymbols().size();
        
        // For very large expressions (>500 symbols), skip AdvancedSimplifier entirely
        // and go straight to recursive DNF approach (Python's reducer_or strategy)
        if (symbolCount > LOGICNG_MAX_SYMBOLS) {
            log.info("  Skipping AdvancedSimplifier (symbols={} > {}), using recursive DNF",
                    symbolCount, LOGICNG_MAX_SYMBOLS);
            return recursiveDnfReduce(expr);
        }
        
        // Convert to LogicNG formula
        Formula formula = toLogicNG(expr);
        
        // Timeout scales with symbol count
        long timeoutSeconds = symbolCount <= COMPLEXITY_THRESHOLD ? 30 
                            : Math.min(60, 10 + symbolCount / 10);
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Formula> future = executor.submit(() -> {
                AdvancedSimplifier simplifier = new AdvancedSimplifier(
                    AdvancedSimplifierConfig.builder().build()
                );
                return formula.transform(simplifier);
            });
            
            Formula simplified = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return fromLogicNG(simplified);
            
        } catch (TimeoutException e) {
            log.warn("  AdvancedSimplifier timeout after {}s (symbols={}), falling back to recursive DNF",
                    timeoutSeconds, symbolCount);
            return recursiveDnfReduce(expr);
        } catch (Exception e) {
            log.warn("  AdvancedSimplifier failed (symbols={}): {}, falling back to recursive DNF",
                    symbolCount, e.getMessage());
            return recursiveDnfReduce(expr);
        } finally {
            executor.shutdownNow();
        }
    }
    
    // ==================== Recursive DNF Reducer (Python's reducer_or approach) ====================
    
    /**
     * Custom DNF reducer with absorption law optimization.
     * Ported from Python's reducer_or.py.
     *
     * This reducer is optimized for large OR expressions (>8 free symbols) where
     * LogicNG's AdvancedSimplifier may be too slow. It applies absorption laws to
     * detect and eliminate redundant disjuncts, then recursively splits and simplifies.
     *
     * For AND expressions, it recursively simplifies sub-expressions first.
     *
     * 3-step algorithm for OR:
     *   1. or_absorption_expression: find the best absorbing sub-expression
     *   2. recursive_dnf_simplify: split OR in half, inject absorber, simplify sub-parts
     *   3. final_dnf_simplify: merge terms sharing the same conjunction base
     */
    private LogicalExpression recursiveDnfReduce(LogicalExpression expr) {
        if (expr == null) {
            return expr;
        }
        
        // For AND expressions: recursively simplify each sub-expression
        // This handles cases like AND(nodeKind, OR(class1, class2, ...))
        if (expr instanceof AndExpr and) {
            List<LogicalExpression> simplifiedArgs = new ArrayList<>();
            boolean changed = false;
            
            for (LogicalExpression arg : and.getArgs()) {
                LogicalExpression simplified = recursiveDnfReduce(arg);
                simplifiedArgs.add(simplified);
                if (simplified != arg) {
                    changed = true;
                }
            }
            
            if (!changed) {
                return expr;
            }
            
            // Rebuild AND with simplified args
            if (simplifiedArgs.size() == 1) {
                return simplifiedArgs.get(0);
            }
            return new AndExpr(simplifiedArgs);
        }
        
        // For non-OR expressions, return as-is
        if (!(expr instanceof OrExpr)) {
            return expr;
        }
        
        OrExpr orExpr = (OrExpr) expr;
        
        // Debug: log OR structure for large expressions
        int symbolCount = expr.getFreeSymbols().size();
        if (symbolCount > 1000) {
            int numSymbols = 0;
            int numAnds = 0;
            for (LogicalExpression arg : orExpr.getArgs()) {
                if (arg instanceof SymbolExpr) numSymbols++;
                else if (arg instanceof AndExpr) numAnds++;
            }
            log.info("  Large OR: {} args, {} pure symbols, {} ANDs (symbols={})", 
                    orExpr.getArgs().size(), numSymbols, numAnds, symbolCount);
        }
        
        long t0 = System.currentTimeMillis();
        
        // Step 1: Find absorbing expression
        LogicalExpression absorb = orAbsorptionExpression(expr);
        
        // Step 2: Recursive DNF simplify
        LogicalExpression result = recursiveDnfSimplify(expr, absorb);
        
        // Step 3: Final DNF simplify (merge terms with same conjunction base)
        result = finalDnfSimplify(result);
        
        long elapsed = System.currentTimeMillis() - t0;
        int resultSymbols = result != null ? result.getFreeSymbols().size() : 0;
        if (elapsed > 100) {
            log.info("  recursiveDnfReduce took {}ms (result symbols={})", elapsed, resultSymbols);
        }
        
        return result;
    }
    
    /**
     * Find the best candidate absorption expression from an OR expression.
     * A disjunct A absorbs disjunct B if A's direct args ⊆ B's direct args (A ∨ B = A).
     * Returns the candidate that absorbs the most other disjuncts.
     * 
     * Ported from Python's or_absorption_expression().
     * 
     * CRITICAL: Must use getDirectArgs() not getTermSymbolsDeep()!
     * Python uses set(expr.args) which for a single symbol returns EMPTY set.
     * Empty set is subset of everything, so single symbols can absorb all AND expressions
     * containing them. For example:
     *   OR(AND(nk, c1), AND(nk, c2), nk)
     *   - set(nk.args) = {} (empty)
     *   - set(AND(nk,c1).args) = {nk, c1}
     *   - {}.issubset({nk, c1}) = True, so nk absorbs AND(nk, c1)
     */
    private LogicalExpression orAbsorptionExpression(LogicalExpression expr) {
        if (!(expr instanceof OrExpr or)) {
            return null;
        }
        
        List<LogicalExpression> disjuncts = or.getArgs();
        int totalDisjuncts = disjuncts.size();
        
        // Debug: log large OR expressions
        if (totalDisjuncts > 100) {
            log.info("  orAbsorptionExpression: {} disjuncts", totalDisjuncts);
        }
        
        // Extract DIRECT args for each disjunct (matches Python's set(d.args))
        // For single symbol: empty set
        // For AND(a, b): {a, b}
        List<Set<LogicalExpression>> disjunctArgSets = new ArrayList<>();
        for (LogicalExpression d : disjuncts) {
            disjunctArgSets.add(getDirectArgs(d));
        }
        
        // Debug: Check for potential absorbers (single symbols have empty arg sets)
        long emptyArgSetCount = disjunctArgSets.stream().filter(Set::isEmpty).count();
        if (emptyArgSetCount > 0 && totalDisjuncts > 100) {
            log.info("  Found {} potential absorbers (single symbols with empty args)", emptyArgSetCount);
        }
        
        // Count how many other disjuncts each disjunct absorbs
        // CRITICAL: Must be PROPER subset (i.args ⊂ j.args), not just subset!
        // For absorption A ∨ (A ∧ B) = A, we need A's args to be strictly smaller
        Map<Integer, Integer> candidateCounts = new HashMap<>();
        for (int i = 0; i < disjuncts.size(); i++) {
            for (int j = 0; j < disjuncts.size(); j++) {
                if (i != j) {
                    Set<LogicalExpression> iArgs = disjunctArgSets.get(i);
                    Set<LogicalExpression> jArgs = disjunctArgSets.get(j);
                    // Check if i's args are PROPER subset of j's args (i.size < j.size AND j contains all of i)
                    if (iArgs.size() < jArgs.size() && jArgs.containsAll(iArgs)) {
                        candidateCounts.merge(i, 1, Integer::sum);
                    }
                }
            }
        }
        
        if (candidateCounts.isEmpty()) {
            if (totalDisjuncts > 100) {
                log.info("  No absorption candidates found (no proper subsets)");
            }
            return null;
        }
        
        // Select the factor that appears most frequently
        int maxFreq = candidateCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> candidates = candidateCounts.entrySet().stream()
                .filter(e -> e.getValue() == maxFreq)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // If multiple candidates have same max frequency, pick the first one
        // This is valid because if they all have the same frequency, they are equivalent
        // (especially for single symbols with empty args that can all absorb others)
        if (candidates.size() > 1) {
            if (totalDisjuncts > 100) {
                log.info("  Multiple absorption candidates with same frequency ({}), picking first", maxFreq);
            }
        }
        
        // Return the candidate that absorbs the most (pick first if tied)
        LogicalExpression absorber = disjuncts.get(candidates.get(0));
        if (totalDisjuncts > 100) {
            log.info("  Found absorber: {} (absorbs {} other disjuncts)", absorber, maxFreq);
        }
        return absorber;
    }
    
    /**
     * Get direct arguments of an expression (matches Python's expr.args behavior).
     * For Symbol: returns empty set (critical for absorption!)
     * For AND(a, b): returns {a, b}
     * For OR(a, b): returns {a, b}
     * For NOT(a): returns {a}
     */
    private Set<LogicalExpression> getDirectArgs(LogicalExpression expr) {
        if (expr instanceof SymbolExpr) {
            // CRITICAL: Python's symbol.args returns empty tuple, so set(symbol.args) is empty set
            return Collections.emptySet();
        } else if (expr instanceof AndExpr and) {
            return new HashSet<>(and.getArgs());
        } else if (expr instanceof OrExpr or) {
            return new HashSet<>(or.getArgs());
        } else if (expr instanceof NotExpr not) {
            return Set.of(not.getArg());
        }
        return Collections.emptySet();
    }
    
    /**
     * Recursively simplify a large DNF expression by splitting and applying absorption.
     * 
     * Ported from Python's recursive_dnf_simplify().
     * 
     * @param expr    The OR expression to simplify
     * @param absorb  The absorbing expression (can be null)
     * @return Simplified expression
     */
    private LogicalExpression recursiveDnfSimplify(LogicalExpression expr, LogicalExpression absorb) {
        if (expr == null) return null;
        
        // If we have an absorbing expression, first apply absorption to remove redundant terms
        if (absorb != null && expr instanceof OrExpr or) {
            log.info("  recursiveDnfSimplify: applying absorption with absorber={}", absorb);
            // Use getDirectArgs to match Python's absorption logic
            // For single symbol absorber: empty set, which is subset of everything
            Set<LogicalExpression> absorbArgs = getDirectArgs(absorb);
            log.info("  absorber args size: {}", absorbArgs.size());
            List<LogicalExpression> keptArgs = new ArrayList<>();
            int absorbed = 0;
            
            for (LogicalExpression arg : or.getArgs()) {
                Set<LogicalExpression> argArgs = getDirectArgs(arg);
                // If absorb's args are a PROPER subset of arg's args, arg is absorbed
                // Empty set (single symbol) is proper subset of any non-empty set
                // NOTE: Two single symbols (both with empty args) CANNOT absorb each other
                // unless they are the same symbol. OR(A, B) != A unless A=B.
                if (absorbArgs.size() < argArgs.size() && argArgs.containsAll(absorbArgs)) {
                    absorbed++;
                } else {
                    keptArgs.add(arg);
                }
            }
            
            log.info("  absorption check complete: absorbed={}, kept={}", absorbed, keptArgs.size());
            
            if (absorbed > 0) {
                log.info("  Absorption: removed {} terms (absorber: {})", absorbed, absorb);
                
                // Make sure absorber is in the result
                Set<LogicalExpression> absorbArgsCheck = getDirectArgs(absorb);
                boolean hasAbsorber = keptArgs.stream()
                        .anyMatch(arg -> getDirectArgs(arg).equals(absorbArgsCheck));
                if (!hasAbsorber) {
                    keptArgs.add(absorb);
                }
                
                if (keptArgs.size() == 1) {
                    return keptArgs.get(0);
                }
                expr = new OrExpr(keptArgs);
            }
        }
        
        // Get the number of OR args if it's an OR expression
        int numArgs = (expr instanceof OrExpr or) ? or.getArgs().size() : 0;
        
        // Base case: if small enough, simplify directly with LogicNG
        // BUT: For large OR lists (>1000 args), skip LogicNG entirely
        int symbolCount = expr.getFreeSymbols().size();
        log.info("  recursiveDnfSimplify: expr has {} symbols, {} OR args, threshold={}", 
                symbolCount, numArgs, COMPLEXITY_THRESHOLD);
        
        if (numArgs > 1000) {
            // Large OR list: skip LogicNG, just return the deduplicated OR
            log.info("  Large OR list (>1000 args), skipping LogicNG simplification");
            if (expr instanceof OrExpr or) {
                // Deduplicate args
                List<LogicalExpression> uniqueArgs = or.getArgs().stream()
                        .distinct()
                        .collect(Collectors.toList());
                if (uniqueArgs.size() < or.getArgs().size()) {
                    log.info("  Deduplicated OR: {} -> {} args", or.getArgs().size(), uniqueArgs.size());
                }
                return uniqueArgs.size() == 1 ? uniqueArgs.get(0) : new OrExpr(uniqueArgs);
            }
            return expr;
        }
        
        if (symbolCount <= COMPLEXITY_THRESHOLD) {
            log.info("  Small enough, using LogicNG simplification");
            return simplifySmallWithLogicNG(expr);
        }
        
        // Not an OR expression, can't split
        if (!(expr instanceof OrExpr or)) {
            log.info("  Not an OR expression, returning as-is");
            return expr;
        }
        
        // Split into two halves
        List<LogicalExpression> args = or.getArgs();
        int mid = args.size() / 2;
        
        log.info("  Splitting OR with {} args into two halves: left={} args, right={} args", 
                args.size(), mid, args.size() - mid);
        
        LogicalExpression leftPart = mid == 1 ? args.get(0) 
                : new OrExpr(args.subList(0, mid));
        LogicalExpression rightPart = (args.size() - mid) == 1 ? args.get(mid) 
                : new OrExpr(args.subList(mid, args.size()));
        
        log.info("  Recursively simplifying left half...");
        // Recursively simplify each half (absorb already applied above, pass null)
        LogicalExpression simplifiedLeft = recursiveDnfSimplify(leftPart, null);
        log.info("  Left half done, recursively simplifying right half...");
        LogicalExpression simplifiedRight = recursiveDnfSimplify(rightPart, null);
        log.info("  Right half done, merging results...");
        
        // Merge back and check if we can simplify the combined result
        Set<String> leftSyms = simplifiedLeft != null ? simplifiedLeft.getFreeSymbols() : Set.of();
        Set<String> rightSyms = simplifiedRight != null ? simplifiedRight.getFreeSymbols() : Set.of();
        Set<String> allSyms = new HashSet<>(leftSyms);
        allSyms.addAll(rightSyms);
        
        log.info("  Merged result has {} symbols", allSyms.size());
        
        LogicalExpression merged = mergeOrExprs(simplifiedLeft, simplifiedRight);
        int mergedArgs = (merged instanceof OrExpr mor) ? mor.getArgs().size() : 0;
        
        // Skip LogicNG for large OR lists even after merging
        if (mergedArgs > 1000) {
            log.info("  Merged OR still >1000 args, skipping LogicNG");
            return merged;
        }
        
        if (allSyms.size() <= COMPLEXITY_THRESHOLD) {
            // Small enough to combine and simplify
            log.info("  Merged result small enough, simplifying with LogicNG");
            return simplifySmallWithLogicNG(merged);
        } else {
            // Still too large, just combine
            log.info("  Merged result still too large, returning combined OR");
            return merged;
        }
    }
    
    /**
     * Simplify a small expression (≤8 symbols) directly with LogicNG AdvancedSimplifier.
     * No timeout needed for small expressions.
     */
    private LogicalExpression simplifySmallWithLogicNG(LogicalExpression expr) {
        if (expr == null) return null;
        try {
            Formula formula = toLogicNG(expr);
            AdvancedSimplifier simplifier = new AdvancedSimplifier(
                AdvancedSimplifierConfig.builder().build()
            );
            Formula simplified = formula.transform(simplifier);
            return fromLogicNG(simplified);
        } catch (Exception e) {
            log.warn("  small LogicNG simplification failed: {}", e.getMessage());
            return expr;
        }
    }
    
    /**
     * Merge two expressions into an OR.
     */
    private LogicalExpression mergeOrExprs(LogicalExpression left, LogicalExpression right) {
        if (left == null) return right;
        if (right == null) return left;
        
        List<LogicalExpression> args = new ArrayList<>();
        if (left instanceof OrExpr or) {
            args.addAll(or.getArgs());
        } else {
            args.add(left);
        }
        if (right instanceof OrExpr or) {
            args.addAll(or.getArgs());
        } else {
            args.add(right);
        }
        return args.size() == 1 ? args.get(0) : new OrExpr(args);
    }
    
    /**
     * Final DNF simplification: merge terms that share the same conjunction base.
     * 
     * For example: And(A, Or(X,Y)) | And(A, Or(Z,W)) → And(A, Or(X,Y,Z,W))
     * 
     * Ported from Python's final_dnf_simplify().
     */
    private LogicalExpression finalDnfSimplify(LogicalExpression expr) {
        if (expr == null || !(expr instanceof OrExpr or)) {
            return expr;
        }
        
        List<LogicalExpression> terms = or.getArgs();
        
        // Group terms by their AND base (conjunction without OR parts)
        // key = set of non-OR conjuncts, value = accumulated OR terms
        Map<String, List<LogicalExpression>> baseToOrTerms = new LinkedHashMap<>();
        Map<String, List<LogicalExpression>> baseToConjuncts = new LinkedHashMap<>();
        
        for (LogicalExpression term : terms) {
            if (!(term instanceof AndExpr and)) {
                continue; // Skip non-AND terms
            }
            
            List<LogicalExpression> orParts = new ArrayList<>();
            List<LogicalExpression> nonOrParts = new ArrayList<>();
            
            for (LogicalExpression arg : and.getArgs()) {
                if (arg instanceof OrExpr) {
                    orParts.add(arg);
                } else {
                    nonOrParts.add(arg);
                }
            }
            
            if (orParts.isEmpty()) {
                continue; // No OR parts to merge
            }
            
            // Build a canonical key from non-OR parts
            String baseKey = nonOrParts.stream()
                    .map(LogicalExpression::toString)
                    .sorted()
                    .collect(Collectors.joining(","));
            
            // Collect all OR sub-terms
            List<LogicalExpression> flatOrTerms = new ArrayList<>();
            for (LogicalExpression orPart : orParts) {
                if (orPart instanceof OrExpr orExpr) {
                    flatOrTerms.addAll(orExpr.getArgs());
                } else {
                    flatOrTerms.add(orPart);
                }
            }
            
            baseToOrTerms.computeIfAbsent(baseKey, k -> new ArrayList<>()).addAll(flatOrTerms);
            baseToConjuncts.putIfAbsent(baseKey, nonOrParts);
        }
        
        if (baseToOrTerms.isEmpty()) {
            return expr;
        }
        
        // Rebuild: And(base..., Or(merged_or_terms...))
        List<LogicalExpression> simplifiedTerms = new ArrayList<>();
        for (Map.Entry<String, List<LogicalExpression>> entry : baseToOrTerms.entrySet()) {
            List<LogicalExpression> base = baseToConjuncts.get(entry.getKey());
            List<LogicalExpression> orTerms = entry.getValue();
            
            // Deduplicate OR terms
            List<LogicalExpression> uniqueOrTerms = new ArrayList<>(
                    new LinkedHashSet<>(orTerms));
            
            List<LogicalExpression> andArgs = new ArrayList<>(base);
            if (uniqueOrTerms.size() == 1) {
                andArgs.add(uniqueOrTerms.get(0));
            } else {
                andArgs.add(new OrExpr(uniqueOrTerms));
            }
            
            simplifiedTerms.add(andArgs.size() == 1 ? andArgs.get(0) : new AndExpr(andArgs));
        }
        
        if (simplifiedTerms.isEmpty()) {
            return expr;
        }
        
        return simplifiedTerms.size() == 1 ? simplifiedTerms.get(0) : new OrExpr(simplifiedTerms);
    }
    
    /**
     * Get all symbols in a term deeply (including inside AND/OR/NOT).
     * Unlike getTermSymbols which only goes one level into AND.
     */
    private Set<String> getTermSymbolsDeep(LogicalExpression expr) {
        Set<String> symbols = new HashSet<>();
        collectAllSymbols(expr, symbols);
        return symbols;
    }
    
    private void collectAllSymbols(LogicalExpression expr, Set<String> symbols) {
        if (expr instanceof SymbolExpr sym) {
            symbols.add(sym.getName());
        } else if (expr instanceof NotExpr not) {
            collectAllSymbols(not.getArg(), symbols);
        } else if (expr instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) collectAllSymbols(arg, symbols);
        } else if (expr instanceof OrExpr or) {
            for (LogicalExpression arg : or.getArgs()) collectAllSymbols(arg, symbols);
        }
    }
    
    /**
     * Simplify large expressions using subsumption elimination first.
     * Similar to Python's PySAT approach.
     */
    private LogicalExpression simplifyLargeExpression(LogicalExpression expr) {
        int symbolCount = expr.getFreeSymbols().size();
        
        // Debug: log expression type for very large expressions
        if (symbolCount > 1000) {
            String type = expr.getClass().getSimpleName();
            log.info("  simplifyLargeExpression: type={}, symbols={}", type, symbolCount);
            if (expr instanceof AndExpr and) {
                log.info("    AndExpr has {} args:", and.getArgs().size());
                for (int i = 0; i < Math.min(3, and.getArgs().size()); i++) {
                    LogicalExpression arg = and.getArgs().get(i);
                    log.info("      arg[{}]: {} (symbols={})", i, arg.getClass().getSimpleName(), 
                            arg.getFreeSymbols().size());
                }
            }
        }
        
        if (!(expr instanceof OrExpr)) {
            return simplifyWithLogicNG(expr);
        }
        
        OrExpr or = (OrExpr) expr;
        List<LogicalExpression> args = or.getArgs();
        
        // Step 1: Apply subsumption elimination
        // If term A is a subset of term B, remove B (A absorbs B)
        List<LogicalExpression> filtered = applySubsumption(args);
        
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        
        if (filtered.isEmpty()) {
            return expr;
        }
        
        // Step 2: Apply LogicNG simplification on the reduced expression
        LogicalExpression reduced = filtered.size() == args.size() 
            ? expr 
            : new OrExpr(filtered);
        
        return simplifyWithLogicNG(reduced);
    }
    
    /**
     * Apply subsumption elimination (absorption law optimization).
     * If term A ⊆ term B, then A ∨ B = A (remove B).
     * 
     * This is the key optimization from Python's PySAT implementation.
     */
    private List<LogicalExpression> applySubsumption(List<LogicalExpression> terms) {
        // Extract symbol sets for each term
        List<Set<String>> symbolSets = new ArrayList<>();
        for (LogicalExpression term : terms) {
            symbolSets.add(getTermSymbols(term));
        }
        
        // Sort by size (smaller terms first - they're more general)
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            indices.add(i);
        }
        indices.sort(Comparator.comparingInt(i -> symbolSets.get(i).size()));
        
        // Keep track of which terms to keep
        Set<Integer> keep = new HashSet<>();
        
        for (int idx : indices) {
            Set<String> termSymbols = symbolSets.get(idx);
            boolean subsumed = false;
            
            // Check if this term is subsumed by any kept term
            for (int keptIdx : keep) {
                Set<String> keptSymbols = symbolSets.get(keptIdx);
                if (keptSymbols.size() < termSymbols.size() && 
                    termSymbols.containsAll(keptSymbols)) {
                    // Kept term is a subset of this term, so this term is absorbed
                    subsumed = true;
                    break;
                }
            }
            
            if (!subsumed) {
                keep.add(idx);
            }
        }
        
        // Build result list preserving original order
        List<LogicalExpression> result = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            if (keep.contains(i)) {
                result.add(terms.get(i));
            }
        }
        
        return result;
    }
    
    /**
     * Get the symbols in a term (for subsumption check).
     */
    private Set<String> getTermSymbols(LogicalExpression term) {
        Set<String> symbols = new HashSet<>();
        
        if (term instanceof SymbolExpr sym) {
            symbols.add(sym.getName());
        } else if (term instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) {
                if (arg instanceof SymbolExpr sym) {
                    symbols.add(sym.getName());
                }
            }
        }
        
        return symbols;
    }
    
    /**
     * Convert our LogicalExpression to LogicNG Formula.
     * Package-private for use by ConstraintMerger in batch parallel processing.
     */
    Formula toLogicNG(LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            return getOrCreateVar(sym.getName());
            
        } else if (expr instanceof OrExpr or) {
            List<Formula> args = or.getArgs().stream()
                    .map(this::toLogicNG)
                    .collect(Collectors.toList());
            return factory.or(args);
            
        } else if (expr instanceof AndExpr and) {
            List<Formula> args = and.getArgs().stream()
                    .map(this::toLogicNG)
                    .collect(Collectors.toList());
            return factory.and(args);
            
        } else if (expr instanceof NotExpr not) {
            return factory.not(toLogicNG(not.getArg()));
            
        } else if (expr instanceof ConstantExpr constant) {
            return constant.getValue() ? factory.verum() : factory.falsum();
            
        } else {
            throw new IllegalArgumentException("Unknown expression type: " + expr.getClass());
        }
    }
    
    /**
     * Public method to convert LogicalExpression to Formula.
     * Used by ConstraintMerger for batch parallel processing.
     */
    public Formula toFormula(LogicalExpression expr) {
        return toLogicNG(expr);
    }
    
    /**
     * Public method to convert Formula back to LogicalExpression.
     * Used by ConstraintMerger for batch parallel processing.
     */
    public LogicalExpression fromFormula(Formula formula) {
        return fromLogicNG(formula);
    }
    
    /**
     * Convert LogicNG Formula back to our LogicalExpression.
     */
    private LogicalExpression fromLogicNG(Formula formula) {
        switch (formula.type()) {
            case LITERAL:
                Literal lit = (Literal) formula;
                LogicalExpression sym = new SymbolExpr(lit.name());
                return lit.phase() ? sym : new NotExpr(sym);
                
            case OR:
                List<LogicalExpression> orArgs = new ArrayList<>();
                for (Formula sub : formula) {
                    orArgs.add(fromLogicNG(sub));
                }
                if (orArgs.size() == 1) return orArgs.get(0);
                return new OrExpr(orArgs);
                
            case AND:
                List<LogicalExpression> andArgs = new ArrayList<>();
                for (Formula sub : formula) {
                    andArgs.add(fromLogicNG(sub));
                }
                if (andArgs.size() == 1) return andArgs.get(0);
                return new AndExpr(andArgs);
                
            case NOT:
                return new NotExpr(fromLogicNG(((Not) formula).operand()));
                
            case TRUE:
                log.debug("Formula simplified to TRUE");
                return null;
                
            case FALSE:
                log.warn("Formula simplified to FALSE - constraint is unsatisfiable");
                return null;
                
            default:
                throw new IllegalArgumentException("Cannot convert formula type: " + formula.type());
        }
    }
    
    private Variable getOrCreateVar(String name) {
        return varCache.computeIfAbsent(name, factory::variable);
    }
    
    // ==================== Cache Management ====================
    
    /**
     * Clear the expression cache.
     */
    public void clearCache() {
        patternCache.clear();
        
        cacheMisses.set(0);
        varCache.clear();
        patternCache.clear();
    }
    
    /**
     * Get cache statistics including pattern cache stats.
     */
    public CacheStats getCacheStats() {
        long patternHits = patternCacheHits.get();
        long misses = cacheMisses.get();
        long total = patternHits + misses;
        double hitRate = total > 0 ? (double) patternHits / total : 0.0;
        
        PatternCache.CacheStats patternStats = patternCache.getStats();
        
        return new CacheStats(0, patternHits, misses, hitRate, 
                              0, patternStats.size());
    }
    
    /**
     * Get the symbol table.
     */
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }
    
    // ==================== Smart Parallel Simplification ====================
    
    /**
     * Estimate the simplification complexity of a formula.
     * Uses multiple factors: symbol count, depth, operator count.
     * Higher values = more expensive to simplify.
     */
    public int estimateComplexity(Formula formula) {
        if (formula == null) return 0;
        
        int[] stats = new int[3]; // [symbolCount, depth, operatorCount]
        computeFormulaStats(formula, 0, stats);
        
        // Weight: depth has highest impact, then operators, then symbols
        return stats[1] * 100 + stats[2] * 10 + stats[0];
    }
    
    private void computeFormulaStats(Formula formula, int currentDepth, int[] stats) {
        if (formula instanceof Variable || formula instanceof Literal) {
            stats[0]++; // symbol count
            stats[1] = Math.max(stats[1], currentDepth); // max depth
            return;
        }
        
        if (formula instanceof Not) {
            stats[2]++; // operator count
            computeFormulaStats(((Not) formula).operand(), currentDepth + 1, stats);
        } else if (formula instanceof BinaryOperator) {
            stats[2]++; // operator count
            computeFormulaStats(((BinaryOperator) formula).left(), currentDepth + 1, stats);
            computeFormulaStats(((BinaryOperator) formula).right(), currentDepth + 1, stats);
        } else if (formula instanceof NAryOperator) {
            stats[2]++; // operator count
            for (Formula operand : formula) {
                computeFormulaStats(operand, currentDepth + 1, stats);
            }
        }
    }
    
    /**
     * A work item for parallel simplification with complexity estimation.
     */
    public static class SimplifyTask<T> {
        public final T key;
        public final Formula formula;
        public final int complexity;
        public Formula result;
        
        public SimplifyTask(T key, Formula formula, int complexity) {
            this.key = key;
            this.formula = formula;
            this.complexity = complexity;
        }
    }
    
    /**
     * Simplify a batch of formulas in parallel with workload balancing.
     * Divides work based on estimated complexity, not just count.
     * 
     * @param formulas Map of key -> formula to simplify
     * @param parallelism Number of threads (0 or negative = use available processors)
     * @return Map of key -> simplified formula
     */
    public <T> Map<T, Formula> simplifyBatchParallel(Map<T, Formula> formulas, int parallelism) {
        if (formulas == null || formulas.isEmpty()) {
            return Collections.emptyMap();
        }
        
        if (parallelism <= 0) {
            parallelism = Runtime.getRuntime().availableProcessors();
        }
        
        // Single formula - no parallelism needed
        if (formulas.size() == 1) {
            Map<T, Formula> result = new HashMap<>();
            formulas.forEach((k, v) -> result.put(k, simplifyFormula(v)));
            return result;
        }
        
        log.debug("Smart parallel simplify: {} formulas with {} threads", 
                    formulas.size(), parallelism);
        
        // Step 1: Estimate complexity for all formulas
        List<SimplifyTask<T>> tasks = new ArrayList<>();
        for (Map.Entry<T, Formula> entry : formulas.entrySet()) {
            int complexity = estimateComplexity(entry.getValue());
            tasks.add(new SimplifyTask<>(entry.getKey(), entry.getValue(), complexity));
        }
        
        // Step 2: Sort by complexity descending (large tasks first for better load balancing)
        tasks.sort((a, b) -> Integer.compare(b.complexity, a.complexity));
        
        // Step 3: Distribute tasks using greedy bin packing
        // Each bin's target load = totalComplexity / parallelism
        List<List<SimplifyTask<T>>> partitions = distributeWorkload(tasks, parallelism);
        
        if (log.isDebugEnabled()) {
            log.debug("Workload distribution:");
            for (int i = 0; i < partitions.size(); i++) {
                int partitionLoad = partitions.get(i).stream().mapToInt(t -> t.complexity).sum();
                log.debug("  Thread {}: {} tasks, total complexity = {}", 
                            i, partitions.get(i).size(), partitionLoad);
            }
        }
        
        // Step 4: Execute in parallel using ForkJoinPool with work-stealing
        @SuppressWarnings("resource")  // Pool is properly shutdown in finally block
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.submit(() -> 
                partitions.parallelStream().forEach(partition -> {
                    for (SimplifyTask<T> task : partition) {
                        try {
                            task.result = simplifyFormula(task.formula);
                        } catch (Exception e) {
                            log.error("Error simplifying formula: {}", e.getMessage());
                            task.result = task.formula; // Keep original on error
                        }
                    }
                })
            ).join();
        } finally {
            pool.shutdown();
        }
        
        // Step 5: Collect results
        Map<T, Formula> results = new ConcurrentHashMap<>();
        for (SimplifyTask<T> task : tasks) {
            results.put(task.key, task.result != null ? task.result : task.formula);
        }
        
        return results;
    }
    
    /**
     * Internal method to simplify a Formula directly.
     * Uses caching and LogicNG simplification.
     */
    private Formula simplifyFormula(Formula formula) {
        if (formula == null) {
            return null;
        }
        
        // Apply LogicNG simplification
        AdvancedSimplifier advSimplifier = new AdvancedSimplifier(
            AdvancedSimplifierConfig.builder().build()
        );
        Formula simplified = formula.transform(advSimplifier);
        
        return simplified;
    }
    
    /**
     * Distribute tasks across partitions using greedy bin packing.
     * Each partition targets equal total complexity.
     */
    private <T> List<List<SimplifyTask<T>>> distributeWorkload(
            List<SimplifyTask<T>> tasks, int numPartitions) {
        
        // Initialize partitions with their current load
        List<List<SimplifyTask<T>>> partitions = new ArrayList<>();
        long[] partitionLoads = new long[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            partitions.add(new ArrayList<>());
        }
        
        // Greedy assignment: always add to the partition with lowest current load
        for (SimplifyTask<T> task : tasks) {
            // Find partition with minimum load
            int minIdx = 0;
            for (int i = 1; i < numPartitions; i++) {
                if (partitionLoads[i] < partitionLoads[minIdx]) {
                    minIdx = i;
                }
            }
            partitions.get(minIdx).add(task);
            partitionLoads[minIdx] += task.complexity;
        }
        
        // Remove empty partitions
        partitions.removeIf(List::isEmpty);
        
        return partitions;
    }
    
    /**
     * Simplify a list of formulas in parallel.
     * Simplified version that returns results in same order as input.
     */
    public List<Formula> simplifyListParallel(List<Formula> formulas, int parallelism) {
        if (formulas == null || formulas.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Create map with indices as keys
        Map<Integer, Formula> indexedFormulas = IntStream.range(0, formulas.size())
                .boxed()
                .collect(Collectors.toMap(Function.identity(), formulas::get));
        
        // Simplify in parallel
        Map<Integer, Formula> results = simplifyBatchParallel(indexedFormulas, parallelism);
        
        // Restore order
        return IntStream.range(0, formulas.size())
                .mapToObj(results::get)
                .collect(Collectors.toList());
    }
    
    // ==================== Cache Statistics ====================
    
    public record CacheStats(long hits, long patternHits, long misses, double hitRate, 
                             int exactCacheSize, int patternCacheSize) {
        @Override
        public String toString() {
            return String.format(
                "CacheStats[exactHits=%d, patternHits=%d, misses=%d, hitRate=%.2f%%, " +
                "exactCache=%d, patternCache=%d]",
                hits, patternHits, misses, hitRate * 100, exactCacheSize, patternCacheSize);
        }
        
        /** For backward compatibility */
        public long hits() { return hits + patternHits; }
    }
}
