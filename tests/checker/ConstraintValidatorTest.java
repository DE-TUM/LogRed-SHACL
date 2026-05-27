package checker;

import logic.*;
import model.PropertyConstraint;
import org.apache.jena.rdf.model.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConstraintValidator - validates SHACL constraint definitions.
 */
public class ConstraintValidatorTest {
    
    private ConstraintValidator validator;
    
    @BeforeEach
    void setUp() {
        validator = new ConstraintValidator();
    }
    
    // ========== Count Constraint Tests ==========
    
    @Test
    void testValidCountConstraints() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(1);
        pc.setMaxCount(5);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
    }
    
    @Test
    void testMinCountGreaterThanMaxCount() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(10);
        pc.setMaxCount(5);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
        assertEquals(1, validator.getErrors(issues).size());
        assertTrue(issues.get(0).message().contains("minCount"));
        assertTrue(issues.get(0).message().contains("maxCount"));
    }
    
    @Test
    void testEqualMinMaxCount() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(5);
        pc.setMaxCount(5);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
    }
    
    // ========== Length Constraint Tests ==========
    
    @Test
    void testMinLengthGreaterThanMaxLength() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, 10);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH, 5);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("minLength")));
    }
    
    // ========== Numeric Range Tests ==========
    
    @Test
    void testMinInclusiveGreaterThanMaxInclusive() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_INCLUSIVE, 100);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_INCLUSIVE, 50);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
    }
    
    @Test
    void testMinExclusiveGreaterThanOrEqualMaxExclusive() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_EXCLUSIVE, 100);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_EXCLUSIVE, 100);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
    }
    
    @Test
    void testMixedInclusiveExclusiveConflict() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_INCLUSIVE, 100);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_EXCLUSIVE, 100);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
    }
    
    // ========== Redundancy Tests ==========
    
    @Test
    void testRedundantMinCountZero() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(0);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
        assertEquals(1, validator.getRedundant(issues).size());
        assertTrue(issues.get(0).message().contains("minCount=0"));
    }
    
    @Test
    void testRedundantMinLengthZero() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, 0);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
        assertEquals(1, validator.getRedundant(issues).size());
    }
    
    // ========== Logical Expression Tests ==========
    
    @Test
    void testAndWithMultipleDatatypes() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // And(dt_1, dt_2) - impossible
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("dt_2"));
        pc.setLogicalExpression(new AndExpr(args));
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("multiple datatypes")));
    }
    
    @Test
    void testAndWithMultipleNodeKinds() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // And(nk_1, nk_2) - impossible
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("nk_1"));
        args.add(new SymbolExpr("nk_2"));
        pc.setLogicalExpression(new AndExpr(args));
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("multiple nodeKinds")));
    }
    
    @Test
    void testValidAndExpression() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // And(dt_1, nk_1) - valid
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("nk_1"));
        pc.setLogicalExpression(new AndExpr(args));
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
    }
    
    @Test
    void testOrWithValidBranches() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // Or(dt_1, dt_2) - valid (alternative datatypes)
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("dt_2"));
        pc.setLogicalExpression(new OrExpr(args));
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
    }
    
    // ========== sh:in Tests ==========
    
    @Test
    void testEmptyInList() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.IN, new ArrayList<>());
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("empty list")));
    }
    
    @Test
    void testNonEmptyInList() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        List<Object> values = new ArrayList<>();
        values.add("value1");
        values.add("value2");
        pc.setConstraint(PropertyConstraint.ConstraintType.IN, values);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues));
    }
    
    // ========== Combined Tests ==========
    
    @Test
    void testMultipleIssues() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(10);
        pc.setMaxCount(5);  // Error: minCount > maxCount
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, 0);  // Redundant
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues));
        assertEquals(1, validator.getErrors(issues).size());
        assertEquals(1, validator.getRedundant(issues).size());
    }
    
    @Test
    void testNoIssues() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(1);
        pc.setMaxCount(10);
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, 5);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH, 100);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(issues.isEmpty());
    }
    
    // ========== Logging Test ==========
    
    @Test
    void testLogIssues() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(10);
        pc.setMaxCount(5);
        
        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        
        // Should not throw
        assertDoesNotThrow(() -> validator.logIssues(issues));
    }

    // ========== Idempotent / Duplicate Symbol Tests ==========

    @Test
    void testAndWithDuplicateNodeKindNotConflict() {
        // AND(nk_4, nk_4) — 相同 nodeKind 重复，不应误判为冲突
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));

        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("nk_4"));
        args.add(new SymbolExpr("nk_4"));
        pc.setLogicalExpression(new AndExpr(args));

        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues),
            "AND(nk_4, nk_4) is idempotent and must NOT be reported as a conflict");
    }

    @Test
    void testAndWithDuplicateDatatypeNotConflict() {
        // AND(dt_1, dt_1) — 相同 datatype 重复，不应误判为冲突
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));

        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("dt_1"));
        pc.setLogicalExpression(new AndExpr(args));

        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues),
            "AND(dt_1, dt_1) is idempotent and must NOT be reported as a conflict");
    }

    @Test
    void testAndWithThreeDuplicateNodeKindsNotConflict() {
        // AND(nk_4, nk_4, nk_4) — 多个相同值，仍不应报错
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));

        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("nk_4"));
        args.add(new SymbolExpr("nk_4"));
        args.add(new SymbolExpr("nk_4"));
        pc.setLogicalExpression(new AndExpr(args));

        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertFalse(validator.hasErrors(issues),
            "AND(nk_4, nk_4, nk_4) is idempotent and must NOT be reported as a conflict");
    }

    @Test
    void testAndWithDistinctNodeKindsIsConflict() {
        // AND(nk_1, nk_2) — 不同的 nodeKind，确实是冲突
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));

        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("nk_1"));
        args.add(new SymbolExpr("nk_2"));
        pc.setLogicalExpression(new AndExpr(args));

        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues),
            "AND(nk_1, nk_2) with distinct nodeKinds MUST be reported as a conflict");
    }

    @Test
    void testAndWithDistinctDatatypesIsConflict() {
        // AND(dt_1, dt_2) — 不同的 datatype，确实是冲突
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));

        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("dt_2"));
        pc.setLogicalExpression(new AndExpr(args));

        List<ConstraintValidator.ValidationIssue> issues = validator.validate(pc);
        assertTrue(validator.hasErrors(issues),
            "AND(dt_1, dt_2) with distinct datatypes MUST be reported as a conflict");
    }
}
