package checker;

import logic.*;
import model.PropertyConstraint;
import model.PropertyConstraint.ConstraintType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Validates SHACL constraint definitions for logical consistency.
 * This is NOT a data validator - it validates the constraint definitions themselves.
 * 
 * Detects issues like:
 * - minCount > maxCount (unsatisfiable)
 * - Multiple datatypes in AND (impossible)
 * - Empty sh:in list (unsatisfiable)
 * - Redundant constraints (minCount=0, minLength=0)
 */
public class ConstraintValidator {
    private static final Logger log = LoggerFactory.getLogger(ConstraintValidator.class);
    
    public enum IssueSeverity {
        ERROR,      // Semantic conflict - constraint is unsatisfiable
        WARNING,    // Potential issue - may indicate a mistake
        INFO,       // Informational - valid but noteworthy
        REDUNDANT   // Redundant constraint - can be safely removed
    }
    
    public record ValidationIssue(
        IssueSeverity severity,
        String message,
        String path
    ) {}
    
    /**
     * Validate a PropertyConstraint for logical consistency.
     * @return List of validation issues found (empty if no issues)
     */
    public List<ValidationIssue> validate(PropertyConstraint pc) {
        List<ValidationIssue> issues = new ArrayList<>();
        String path = pc.getPath() != null ? pc.getPath().getLocalName() : "unknown";
        
        // Check count constraints
        validateCountConstraints(pc, path, issues);
        
        // Check length constraints
        validateLengthConstraints(pc, path, issues);
        
        // Check numeric range constraints
        validateNumericRangeConstraints(pc, path, issues);
        
        // Check redundant constraints
        checkRedundantConstraints(pc, path, issues);
        
        // Check logical expression conflicts
        if (pc.getLogicalExpression() != null) {
            validateLogicalExpression(pc.getLogicalExpression(), path, issues);
        }
        
        // Check sh:in constraints
        validateInConstraint(pc, path, issues);
        
        return issues;
    }
    
    private void validateCountConstraints(PropertyConstraint pc, String path, List<ValidationIssue> issues) {
        Integer minCount = pc.getMinCount();
        Integer maxCount = pc.getMaxCount();
        
        if (minCount != null && maxCount != null && minCount > maxCount) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("minCount (%d) > maxCount (%d) - constraint is unsatisfiable", minCount, maxCount),
                path
            ));
        }
    }
    
    private void validateLengthConstraints(PropertyConstraint pc, String path, List<ValidationIssue> issues) {
        Integer minLength = (Integer) pc.getConstraint(ConstraintType.MIN_LENGTH);
        Integer maxLength = (Integer) pc.getConstraint(ConstraintType.MAX_LENGTH);
        
        if (minLength != null && maxLength != null && minLength > maxLength) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("minLength (%d) > maxLength (%d) - constraint is unsatisfiable", minLength, maxLength),
                path
            ));
        }
    }
    
    private void validateNumericRangeConstraints(PropertyConstraint pc, String path, List<ValidationIssue> issues) {
        Number minInc = (Number) pc.getConstraint(ConstraintType.MIN_INCLUSIVE);
        Number maxInc = (Number) pc.getConstraint(ConstraintType.MAX_INCLUSIVE);
        Number minExc = (Number) pc.getConstraint(ConstraintType.MIN_EXCLUSIVE);
        Number maxExc = (Number) pc.getConstraint(ConstraintType.MAX_EXCLUSIVE);
        
        // Check min > max for inclusive
        if (minInc != null && maxInc != null && minInc.doubleValue() > maxInc.doubleValue()) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("minInclusive (%s) > maxInclusive (%s) - constraint is unsatisfiable", minInc, maxInc),
                path
            ));
        }
        
        // Check exclusive range conflicts
        if (minExc != null && maxExc != null && minExc.doubleValue() >= maxExc.doubleValue()) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("minExclusive (%s) >= maxExclusive (%s) - constraint is unsatisfiable", minExc, maxExc),
                path
            ));
        }
        
        // Check mixed inclusive/exclusive conflicts
        if (minInc != null && maxExc != null && minInc.doubleValue() >= maxExc.doubleValue()) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("minInclusive (%s) >= maxExclusive (%s) - constraint is unsatisfiable", minInc, maxExc),
                path
            ));
        }
        
        if (minExc != null && maxInc != null && minExc.doubleValue() >= maxInc.doubleValue()) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("minExclusive (%s) >= maxInclusive (%s) - constraint is unsatisfiable", minExc, maxInc),
                path
            ));
        }
    }
    
    private void checkRedundantConstraints(PropertyConstraint pc, String path, List<ValidationIssue> issues) {
        Integer minCount = pc.getMinCount();
        if (minCount != null && minCount == 0) {
            issues.add(new ValidationIssue(
                IssueSeverity.REDUNDANT,
                "minCount=0 is redundant (default behavior)",
                path
            ));
        }
        
        Integer minLength = (Integer) pc.getConstraint(ConstraintType.MIN_LENGTH);
        if (minLength != null && minLength == 0) {
            issues.add(new ValidationIssue(
                IssueSeverity.REDUNDANT,
                "minLength=0 is redundant (all strings have length >= 0)",
                path
            ));
        }
    }
    
    private void validateLogicalExpression(LogicalExpression expr, String path, List<ValidationIssue> issues) {
        if (expr instanceof AndExpr andExpr) {
            validateAndExpression(andExpr, path, issues);
        } else if (expr instanceof OrExpr orExpr) {
            validateOrExpression(orExpr, path, issues);
        }
    }
    
    private void validateAndExpression(AndExpr andExpr, String path, List<ValidationIssue> issues) {
        Set<String> datatypes = new LinkedHashSet<>();
        Set<String> nodeKinds = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        
        for (LogicalExpression arg : andExpr.getArgs()) {
            if (arg instanceof SymbolExpr sym) {
                String name = sym.getName();
                if (name.startsWith("dt_")) {
                    datatypes.add(name);
                } else if (name.startsWith("nk_")) {
                    nodeKinds.add(name);
                } else if (name.startsWith("cls_")) {
                    classes.add(name);
                }
            } else if (arg instanceof AndExpr nested) {
                // Recursively check nested AND
                validateAndExpression(nested, path, issues);
            }
        }
        
        // Multiple distinct datatypes in AND is impossible
        if (datatypes.size() > 1) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("And of multiple datatypes %s - impossible to satisfy (a value can only have one datatype)", datatypes),
                path
            ));
        }
        
        // Multiple distinct nodeKinds in AND is impossible
        if (nodeKinds.size() > 1) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                String.format("And of multiple nodeKinds %s - impossible to satisfy (a value can only have one node kind)", nodeKinds),
                path
            ));
        }
    }
    
    private void validateOrExpression(OrExpr orExpr, String path, List<ValidationIssue> issues) {
        // Check if all branches are unsatisfiable (would make whole OR unsatisfiable)
        // This is complex and would require full satisfiability checking
        // For now, just recurse into branches
        for (LogicalExpression arg : orExpr.getArgs()) {
            validateLogicalExpression(arg, path, issues);
        }
    }
    
    private void validateInConstraint(PropertyConstraint pc, String path, List<ValidationIssue> issues) {
        List<?> inValues = (List<?>) pc.getConstraint(ConstraintType.IN);
        if (inValues != null && inValues.isEmpty()) {
            issues.add(new ValidationIssue(
                IssueSeverity.ERROR,
                "sh:in with empty list - no value can satisfy this constraint",
                path
            ));
        }
    }
    
    /**
     * Log all issues at appropriate levels.
     */
    public void logIssues(List<ValidationIssue> issues) {
        for (ValidationIssue issue : issues) {
            String msg = String.format("[%s] %s (path: %s)", 
                issue.severity(), issue.message(), issue.path());
            switch (issue.severity()) {
                case ERROR -> log.error(msg);
                case WARNING -> log.warn(msg);
                case INFO, REDUNDANT -> log.info(msg);
            }
        }
        
        // Summary
        long errors = issues.stream().filter(i -> i.severity() == IssueSeverity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity() == IssueSeverity.WARNING).count();
        long info = issues.stream().filter(i -> i.severity() == IssueSeverity.INFO).count();
        long redundant = issues.stream().filter(i -> i.severity() == IssueSeverity.REDUNDANT).count();
        
        if (!issues.isEmpty()) {
            log.info("Validation: {} errors, {} warnings, {} info, {} redundant", 
                errors, warnings, info, redundant);
        }
    }
    
    /**
     * Check if any issues are errors (unsatisfiable constraints).
     */
    public boolean hasErrors(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == IssueSeverity.ERROR);
    }
    
    /**
     * Get only error-level issues.
     */
    public List<ValidationIssue> getErrors(List<ValidationIssue> issues) {
        return issues.stream()
            .filter(i -> i.severity() == IssueSeverity.ERROR)
            .toList();
    }
    
    /**
     * Get only redundant constraints.
     */
    public List<ValidationIssue> getRedundant(List<ValidationIssue> issues) {
        return issues.stream()
            .filter(i -> i.severity() == IssueSeverity.REDUNDANT)
            .toList();
    }
}
