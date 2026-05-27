package reducer;

import logic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * XOR pattern detector and optimizer.
 * 
 * Corresponds to Python's _is_xor_pattern() and simplify_xone_alternatives().
 * 
 * XOR pattern: (A & ~B & ~C) | (B & ~A & ~C) | (C & ~A & ~B)
 * Each disjunct has exactly one positive symbol and all others negated.
 */
public class XorOptimizer {
    private static final Logger log = LoggerFactory.getLogger(XorOptimizer.class);
    
    /**
     * Check if expression matches XOR pattern.
     * (A & ~B & ~C...) | (B & ~A & ~C...) | ...
     * Each disjunct should have exactly one positive symbol and all others negated.
     */
    public static boolean isXorPattern(LogicalExpression expr) {
        if (!(expr instanceof OrExpr or)) {
            return false;
        }
        
        List<LogicalExpression> args = or.getArgs();
        
        // Collect all symbols across all disjuncts
        Set<String> allSymbols = new HashSet<>();
        for (LogicalExpression disj : args) {
            collectSymbols(disj, allSymbols);
        }
        
        int nSymbols = allSymbols.size();
        if (nSymbols < 2 || args.size() != nSymbols) {
            return false;
        }
        
        // Check each disjunct: should have exactly 1 positive and (n-1) negatives
        for (LogicalExpression disj : args) {
            Set<String> positives = new HashSet<>();
            Set<String> negatives = new HashSet<>();
            
            if (!extractPositivesAndNegatives(disj, positives, negatives)) {
                return false;
            }
            
            // Each disjunct should have exactly 1 positive and all others negative
            if (positives.size() != 1 || negatives.size() != nSymbols - 1) {
                return false;
            }
            
            // The positive should not be in negatives, and all symbols should be covered
            if (!Collections.disjoint(positives, negatives)) {
                return false;
            }
            
            Set<String> combined = new HashSet<>(positives);
            combined.addAll(negatives);
            if (!combined.equals(allSymbols)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Collect all symbol names from an expression.
     */
    private static void collectSymbols(LogicalExpression expr, Set<String> symbols) {
        if (expr instanceof SymbolExpr sym) {
            symbols.add(sym.getName());
        } else if (expr instanceof NotExpr not) {
            if (not.getArg() instanceof SymbolExpr sym) {
                symbols.add(sym.getName());
            }
        } else if (expr instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) {
                collectSymbols(arg, symbols);
            }
        } else if (expr instanceof OrExpr or) {
            for (LogicalExpression arg : or.getArgs()) {
                collectSymbols(arg, symbols);
            }
        }
    }
    
    /**
     * Extract positive and negative symbols from an expression.
     * Returns false if expression contains non-symbol terms.
     */
    private static boolean extractPositivesAndNegatives(LogicalExpression expr, 
                                                        Set<String> positives, 
                                                        Set<String> negatives) {
        if (expr instanceof SymbolExpr sym) {
            positives.add(sym.getName());
            return true;
        } else if (expr instanceof NotExpr not) {
            if (not.getArg() instanceof SymbolExpr sym) {
                negatives.add(sym.getName());
                return true;
            }
            return false;
        } else if (expr instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) {
                if (!extractPositivesAndNegatives(arg, positives, negatives)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
    
    /**
     * Simplify XOR alternatives by removing duplicate alternatives while preserving
     * at least one duplicate to express contradictions.
     * 
     * For example:
     * - xone(A, A, A, A) → xone(A, A) (preserves contradiction)
     * - xone(A, B, A, A) → xone(A, A, B) (preserves contradiction)
     * - xone(A, B, C) → xone(A, B, C) (no change, no duplicates)
     */
    public static <T> List<T> simplifyXoneAlternatives(List<T> alternatives, 
                                                        String nodeShapeName,
                                                        java.util.function.BiPredicate<T, T> equivalenceChecker) {
        if (alternatives == null || alternatives.size() <= 1) {
            return alternatives;
        }
        
        // Track equivalence classes
        Map<Integer, List<Integer>> equivalenceClasses = new LinkedHashMap<>();
        
        for (int i = 0; i < alternatives.size(); i++) {
            boolean foundClass = false;
            
            for (Map.Entry<Integer, List<Integer>> entry : equivalenceClasses.entrySet()) {
                int classRep = entry.getKey();
                if (equivalenceChecker.test(alternatives.get(i), alternatives.get(classRep))) {
                    entry.getValue().add(i);
                    foundClass = true;
                    break;
                }
            }
            
            if (!foundClass) {
                List<Integer> newClass = new ArrayList<>();
                newClass.add(i);
                equivalenceClasses.put(i, newClass);
            }
        }
        
        // Build simplified alternatives
        List<T> simplified = new ArrayList<>();
        boolean hasContradiction = false;
        
        for (List<Integer> equivGroup : equivalenceClasses.values()) {
            if (equivGroup.size() > 1) {
                hasContradiction = true;
                // Keep first occurrence and one duplicate to express contradiction
                simplified.add(alternatives.get(equivGroup.get(0)));
                simplified.add(alternatives.get(equivGroup.get(1)));
            } else {
                simplified.add(alternatives.get(equivGroup.get(0)));
            }
        }
        
        if (hasContradiction) {
            log.warn("XOR contradiction in {}: {} alternatives reduced to {}",
                    nodeShapeName, alternatives.size(), simplified.size());
        }
        
        return simplified;
    }
    
    /**
     * Preserve XOR patterns during simplification.
     * When simplifying an AND expression, keep embedded XOR patterns intact.
     */
    public static LogicalExpression preserveXorInAnd(AndExpr and, LogicSimplifier simplifier) {
        List<LogicalExpression> xorArgs = new ArrayList<>();
        List<LogicalExpression> otherArgs = new ArrayList<>();
        
        for (LogicalExpression arg : and.getArgs()) {
            if (isXorPattern(arg)) {
                xorArgs.add(arg);
            } else {
                otherArgs.add(arg);
            }
        }
        
        if (xorArgs.isEmpty()) {
            // No XOR patterns, simplify normally
            return simplifier.simplify(and);
        }
        
        // Simplify non-XOR parts
        LogicalExpression simplifiedOther = null;
        if (!otherArgs.isEmpty()) {
            LogicalExpression combined = otherArgs.size() == 1 
                ? otherArgs.get(0) 
                : new AndExpr(otherArgs);
            simplifiedOther = simplifier.simplify(combined);
        }
        
        // Rebuild AND with preserved XOR patterns
        List<LogicalExpression> result = new ArrayList<>();
        if (simplifiedOther != null) {
            result.add(simplifiedOther);
        }
        result.addAll(xorArgs);
        
        if (result.size() == 1) {
            return result.get(0);
        }
        
        return new AndExpr(result);
    }
}
