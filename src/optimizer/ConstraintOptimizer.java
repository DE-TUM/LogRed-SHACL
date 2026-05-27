package optimizer;

import logic.*;
import model.PropertyConstraint;
import model.PropertyConstraint.ConstraintType;
import checker.ConstraintValidator;
import checker.ConstraintValidator.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Optimizes SHACL constraint expressions by:
 * 1. Removing redundant constraints (minCount=0, minLength=0)
 * 2. Simplifying unsatisfiable constraints to FALSE
 * 3. Normalizing OR expressions (sorting, deduplication)
 * 4. Applying distributive law: Or(And(a,b), And(a,c)) → And(a, Or(b,c))
 */
public class ConstraintOptimizer {
    private static final Logger log = LoggerFactory.getLogger(ConstraintOptimizer.class);
    
    @SuppressWarnings("unused")  // Reserved for future use (symbol lookup)
    private final SymbolTable symbolTable;
    private final ConstraintValidator validator;
    
    // Statistics
    private int redundanciesRemoved = 0;
    private int unsatisfiableSimplified = 0;
    private int expressionsNormalized = 0;
    private int distributiveLawApplied = 0;
    
    public ConstraintOptimizer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
        this.validator = new ConstraintValidator();
    }
    
    /**
     * Optimize a PropertyConstraint.
     * @return Optimized copy of the constraint
     */
    public PropertyConstraint optimize(PropertyConstraint pc) {
        PropertyConstraint optimized = pc.copy();

        // 1. Remove redundant attribute constraints
        removeRedundancies(optimized);
        
        // 2. Validate and report conflicts
        List<ValidationIssue> issues = validator.validate(optimized);
        if (validator.hasErrors(issues)) {
            // Log conflicts but preserve original constraints for SHACL round-tripping
            for (ValidationIssue issue : validator.getErrors(issues)) {
                log.warn("Constraint conflict detected: {}", issue.message());
            }
            log.info("Constraint simplified to FALSE due to conflicts");
            optimized.setLogicalExpression(ConstantExpr.FALSE);
            unsatisfiableSimplified++;
            return optimized;
        }
        
        // 3. Optimize logical expression
        if (optimized.getLogicalExpression() != null) {
            LogicalExpression expr = optimized.getLogicalExpression();
            LogicalExpression optimizedExpr = optimizeExpression(expr);
            
            // TRUE -> no logical constraints (empty shape semantics)
            if (optimizedExpr instanceof ConstantExpr &&
                ((ConstantExpr) optimizedExpr).getValue()) {
                optimized.setLogicalExpression(null);
            } else {
                if (optimizedExpr instanceof ConstantExpr &&
                    !((ConstantExpr) optimizedExpr).getValue()) {
                    log.info("Constraint simplified to FALSE due to conflicts");
                    unsatisfiableSimplified++;
                }
                optimized.setLogicalExpression(optimizedExpr);
            }
        }
        
        return optimized;
    }
    
    /**
     * Remove redundant constraints from a PropertyConstraint.
     */
    private void removeRedundancies(PropertyConstraint pc) {
        // minCount=0 is redundant
        if (pc.getMinCount() != null && pc.getMinCount() == 0) {
            pc.setMinCount(null);
            pc.getConstraints().remove(ConstraintType.MIN_COUNT);
            redundanciesRemoved++;
            log.debug("Removed redundant minCount=0");
        }
        
        // minLength=0 is redundant
        Integer minLength = (Integer) pc.getConstraint(ConstraintType.MIN_LENGTH);
        if (minLength != null && minLength == 0) {
            pc.getConstraints().remove(ConstraintType.MIN_LENGTH);
            redundanciesRemoved++;
            log.debug("Removed redundant minLength=0");
        }
    }
    
    /**
     * Optimize a logical expression.
     */
    private LogicalExpression optimizeExpression(LogicalExpression expr) {
        if (expr instanceof AndExpr andExpr) {
            return optimizeAnd(andExpr);
        } else if (expr instanceof OrExpr orExpr) {
            return optimizeOr(orExpr);
        } else if (expr instanceof NotExpr notExpr) {
            return optimizeNot(notExpr);
        }
        return expr;
    }
    
    /**
     * Optimize a NOT expression.
     * - Not(FALSE) → TRUE
     * - Not(TRUE) → FALSE
     */
    private LogicalExpression optimizeNot(NotExpr notExpr) {
        LogicalExpression optimizedArg = optimizeExpression(notExpr.getArg());
        
        // Not(FALSE) → TRUE
        if (optimizedArg instanceof ConstantExpr constant) {
            return constant.getValue() ? ConstantExpr.FALSE : ConstantExpr.TRUE;
        }
        
        return new NotExpr(optimizedArg);
    }
    
    /**
     * Optimize an AND expression.
     */
    private LogicalExpression optimizeAnd(AndExpr andExpr) {
        List<LogicalExpression> optimizedArgs = new ArrayList<>();
        for (LogicalExpression arg : andExpr.getArgs()) {
            LogicalExpression optimizedArg = optimizeExpression(arg);
            
            // If any arg is FALSE, whole AND is FALSE
            if (optimizedArg instanceof ConstantExpr && 
                !((ConstantExpr) optimizedArg).getValue()) {
                return ConstantExpr.FALSE;
            }
            
            // Skip TRUE constants
            if (optimizedArg instanceof ConstantExpr && 
                ((ConstantExpr) optimizedArg).getValue()) {
                continue;
            }
            
            optimizedArgs.add(optimizedArg);
        }
        
        if (optimizedArgs.isEmpty()) {
            return ConstantExpr.TRUE;
        }
        if (optimizedArgs.size() == 1) {
            return optimizedArgs.get(0);
        }
        
        return new AndExpr(optimizedArgs);
    }
    
    /**
     * Optimize an OR expression.
     */
    private LogicalExpression optimizeOr(OrExpr orExpr) {
        List<LogicalExpression> optimizedArgs = new ArrayList<>();
        
        for (LogicalExpression arg : orExpr.getArgs()) {
            LogicalExpression optimizedArg = optimizeExpression(arg);
            
            // If any arg is TRUE, whole OR is TRUE
            if (optimizedArg instanceof ConstantExpr && 
                ((ConstantExpr) optimizedArg).getValue()) {
                return ConstantExpr.TRUE;
            }
            
            // Skip FALSE constants
            if (optimizedArg instanceof ConstantExpr && 
                !((ConstantExpr) optimizedArg).getValue()) {
                continue;
            }
            
            optimizedArgs.add(optimizedArg);
        }
        
        if (optimizedArgs.isEmpty()) {
            return ConstantExpr.FALSE;
        }
        if (optimizedArgs.size() == 1) {
            return optimizedArgs.get(0);
        }
        
        // Normalize: sort and deduplicate
        if (optimizedArgs.size() > 100) {
            long symsBefore = optimizedArgs.stream().filter(a -> a instanceof SymbolExpr).count();
            long andsBefore = optimizedArgs.stream().filter(a -> a instanceof AndExpr).count();
            log.info("optimizeOr before normalize: {} args ({} Symbol, {} AND)", 
                optimizedArgs.size(), symsBefore, andsBefore);
        }
        List<LogicalExpression> normalized = normalize(optimizedArgs);
        if (normalized.size() != optimizedArgs.size()) {
            expressionsNormalized++;
        }
        if (normalized.size() > 100) {
            long symsAfter = normalized.stream().filter(a -> a instanceof SymbolExpr).count();
            long andsAfter = normalized.stream().filter(a -> a instanceof AndExpr).count();
            log.info("optimizeOr after normalize: {} args ({} Symbol, {} AND)", 
                normalized.size(), symsAfter, andsAfter);
        }
        
        // Try to apply distributive law
        LogicalExpression distributed = applyDistributiveLaw(normalized);
        if (distributed != null) {
            distributiveLawApplied++;
            return distributed;
        }
        
        return new OrExpr(normalized);
    }
    
    /**
     * Normalize expressions by deduplicating while preserving original order.
     * Original order preservation is important because SHACL validators like Jena
     * use short-circuit evaluation on sh:or branches — the original order often
     * reflects data frequency, so reordering can degrade validation performance.
     */
    private List<LogicalExpression> normalize(List<LogicalExpression> exprs) {
        // Deduplicate while preserving input order (first occurrence wins)
        List<LogicalExpression> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LogicalExpression expr : exprs) {
            String key = expr.toString();
            if (!seen.contains(key)) {
                seen.add(key);
                deduped.add(expr);
            }
        }
        
        return deduped;
    }
    
    /**
     * Apply distributive law: Or(And(a,b), And(a,c)) → And(a, Or(b,c))
     * Also handles mixed cases where some branches are plain symbols:
     *   Or(And(a,b), And(a,c), a) → And(a, Or(b, c, TRUE)) → a
     * A plain symbol 's' is treated as And(s) for factoring purposes.
     */
    private LogicalExpression applyDistributiveLaw(List<LogicalExpression> orArgs) {
        // All branches must be AND or plain Symbol
        long andCount = orArgs.stream().filter(a -> a instanceof AndExpr).count();
        long symCount = orArgs.stream().filter(a -> a instanceof SymbolExpr).count();
        long otherCount = orArgs.size() - andCount - symCount;
        if (orArgs.size() > 100) {
            log.info("applyDistributiveLaw: {} branches ({} AND, {} Symbol, {} other)", 
                orArgs.size(), andCount, symCount, otherCount);
        }
        if (!orArgs.stream().allMatch(a -> a instanceof AndExpr || a instanceof SymbolExpr)) {
            if (orArgs.size() > 100) {
                log.info("  Skipping: not all branches are AND or Symbol");
            }
            return null;
        }
        
        // Convert each branch to a set of symbols for intersection
        // SymbolExpr "s" is treated as containing just {s}
        // AndExpr AND(s1, s2, ...) contains {s1, s2, ...} (only top-level symbols)
        List<Set<String>> branchSymbolSets = new ArrayList<>();
        for (LogicalExpression arg : orArgs) {
            Set<String> syms = new HashSet<>();
            if (arg instanceof SymbolExpr sym) {
                syms.add(sym.getName());
            } else if (arg instanceof AndExpr and) {
                for (LogicalExpression a : and.getArgs()) {
                    if (a instanceof SymbolExpr s) {
                        syms.add(s.getName());
                    }
                }
            }
            branchSymbolSets.add(syms);
        }
        
        // Find common symbols across ALL branches
        Set<String> commonSymbols = new HashSet<>(branchSymbolSets.get(0));
        for (int i = 1; i < branchSymbolSets.size(); i++) {
            commonSymbols.retainAll(branchSymbolSets.get(i));
        }
        
        if (commonSymbols.isEmpty()) {
            if (orArgs.size() > 100) {
                log.info("  No common symbols found");
            }
            return null;
        }
        
        if (orArgs.size() > 100) {
            log.info("  Common symbols: {}", commonSymbols);
        }
        
        // Extract ALL common symbols and factor them out
        List<LogicalExpression> commonExprs = new ArrayList<>();
        for (String sym : commonSymbols) {
            commonExprs.add(new SymbolExpr(sym));
        }
        
        // Build remaining OR branches (after removing common symbols)
        List<LogicalExpression> remainingBranches = new ArrayList<>();
        for (LogicalExpression arg : orArgs) {
            if (arg instanceof SymbolExpr sym) {
                // Plain symbol: after removing it as common factor, remainder is TRUE
                if (commonSymbols.contains(sym.getName()) && commonSymbols.size() == 1) {
                    // This symbol IS the common factor, remainder is TRUE
                    remainingBranches.add(ConstantExpr.TRUE);
                } else {
                    // Remove common symbols, keep the rest
                    // But a SymbolExpr only has one symbol, so if it's common -> TRUE
                    if (commonSymbols.contains(sym.getName())) {
                        remainingBranches.add(ConstantExpr.TRUE);
                    } else {
                        remainingBranches.add(sym);
                    }
                }
            } else if (arg instanceof AndExpr and) {
                List<LogicalExpression> remaining = and.getArgs().stream()
                    .filter(a -> !(a instanceof SymbolExpr && commonSymbols.contains(((SymbolExpr) a).getName())))
                    .collect(Collectors.toList());
                
                if (remaining.isEmpty()) {
                    remainingBranches.add(ConstantExpr.TRUE);
                } else if (remaining.size() == 1) {
                    remainingBranches.add(remaining.get(0));
                } else {
                    remainingBranches.add(new AndExpr(remaining));
                }
            }
        }
        
        // If any remaining branch is TRUE, the OR simplifies:
        // OR(..., TRUE, ...) = TRUE, so AND(common, TRUE) = common
        boolean hasTrue = remainingBranches.stream()
                .anyMatch(b -> b instanceof ConstantExpr ce && ce.getValue());
        
        if (orArgs.size() > 100) {
            log.info("  hasTrue={}, remainingBranches={}", hasTrue, remainingBranches.size());
        }
        
        if (hasTrue) {
            // AND(common_factors) is the result
            if (commonExprs.size() == 1) {
                return commonExprs.get(0);
            }
            return new AndExpr(commonExprs);
        }
        
        // Build result: And(common_factors..., Or(remaining...))
        LogicalExpression orPart = remainingBranches.size() == 1 
            ? remainingBranches.get(0) 
            : new OrExpr(remainingBranches);
        
        List<LogicalExpression> resultArgs = new ArrayList<>(commonExprs);
        resultArgs.add(orPart);
        return new AndExpr(resultArgs);
    }
    
    // Statistics getters
    public int getRedundanciesRemoved() { return redundanciesRemoved; }
    public int getUnsatisfiableSimplified() { return unsatisfiableSimplified; }
    public int getExpressionsNormalized() { return expressionsNormalized; }
    public int getDistributiveLawApplied() { return distributiveLawApplied; }
    
    public String getStatsSummary() {
        return String.format("%d redundancies removed, %d unsatisfiable simplified, %d expressions normalized, %d distributive law applied",
            redundanciesRemoved, unsatisfiableSimplified, expressionsNormalized, distributiveLawApplied);
    }
    
    public void resetStats() {
        redundanciesRemoved = 0;
        unsatisfiableSimplified = 0;
        expressionsNormalized = 0;
        distributiveLawApplied = 0;
    }
}
