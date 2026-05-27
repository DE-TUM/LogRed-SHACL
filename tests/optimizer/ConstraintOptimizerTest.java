package optimizer;

import logic.*;
import model.PropertyConstraint;
import org.apache.jena.rdf.model.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConstraintOptimizer.
 */
public class ConstraintOptimizerTest {
    
    private SymbolTable symbolTable;
    private ConstraintOptimizer optimizer;
    
    @BeforeEach
    void setUp() {
        symbolTable = new SymbolTable();
        optimizer = new ConstraintOptimizer(symbolTable);
    }
    
    // ========== Redundancy Removal Tests ==========
    
    @Test
    void testRemoveRedundantMinCountZero() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(0);
        pc.setMaxCount(5);
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertNull(optimized.getMinCount());
        assertEquals(5, optimized.getMaxCount());
        assertEquals(1, optimizer.getRedundanciesRemoved());
    }
    
    @Test
    void testRemoveRedundantMinLengthZero() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, 0);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH, 100);
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertNull(optimized.getConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH));
        assertEquals(100, optimized.getConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH));
    }
    
    @Test
    void testKeepNonZeroMinCount() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(1);
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertEquals(1, optimized.getMinCount());
        assertEquals(0, optimizer.getRedundanciesRemoved());
    }
    
    // ========== Unsatisfiable Constraint Tests (Simplified to FALSE, original preserved) ==========
    
    @Test
    void testUnsatisfiableMinMaxCount() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(10);
        pc.setMaxCount(5);
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // Conflicting constraints simplify to FALSE for logical processing
        assertTrue(optimized.getLogicalExpression() instanceof ConstantExpr);
        assertFalse(((ConstantExpr) optimized.getLogicalExpression()).getValue());
        assertEquals(10, optimized.getMinCount());
        assertEquals(5, optimized.getMaxCount());
    }
    
    @Test
    void testUnsatisfiableMinMaxLength() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, 100);
        pc.setConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH, 10);
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertTrue(optimized.getLogicalExpression() instanceof ConstantExpr);
        assertFalse(((ConstantExpr) optimized.getLogicalExpression()).getValue());
        assertEquals(100, optimized.getConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH));
        assertEquals(10, optimized.getConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH));
    }
    
    // ========== AND Expression Tests ==========
    
    @Test
    void testAndWithMultipleDatatypesToFalse() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("dt_2"));
        pc.setLogicalExpression(new AndExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertTrue(optimized.getLogicalExpression() instanceof ConstantExpr);
        assertFalse(((ConstantExpr) optimized.getLogicalExpression()).getValue());
    }
    
    @Test
    void testAndWithMultipleNodeKindsToFalse() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("nk_1"));
        args.add(new SymbolExpr("nk_2"));
        pc.setLogicalExpression(new AndExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertTrue(optimized.getLogicalExpression() instanceof ConstantExpr);
        assertFalse(((ConstantExpr) optimized.getLogicalExpression()).getValue());
    }
    
    @Test
    void testValidAndExpression() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("dt_1"));
        args.add(new SymbolExpr("nk_1"));
        pc.setLogicalExpression(new AndExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertFalse(optimized.getLogicalExpression() instanceof ConstantExpr);
    }
    
    // ========== OR Expression Tests ==========
    
    @Test
    void testOrNormalization() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // Or(b, a, b) -> Or(a, b) after normalization
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("b"));
        args.add(new SymbolExpr("a"));
        args.add(new SymbolExpr("b"));  // Duplicate
        pc.setLogicalExpression(new OrExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertTrue(optimized.getLogicalExpression() instanceof OrExpr);
        OrExpr orExpr = (OrExpr) optimized.getLogicalExpression();
        assertEquals(2, orExpr.getArgs().size());  // Deduplicated
    }
    
    // ========== Distributive Law Tests ==========
    
    @Test
    void testDistributiveLaw() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // Or(And(a, b), And(a, c)) -> And(a, Or(b, c))
        List<LogicalExpression> and1 = new ArrayList<>();
        and1.add(new SymbolExpr("a"));
        and1.add(new SymbolExpr("b"));
        
        List<LogicalExpression> and2 = new ArrayList<>();
        and2.add(new SymbolExpr("a"));
        and2.add(new SymbolExpr("c"));
        
        List<LogicalExpression> orArgs = new ArrayList<>();
        orArgs.add(new AndExpr(and1));
        orArgs.add(new AndExpr(and2));
        pc.setLogicalExpression(new OrExpr(orArgs));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertTrue(optimized.getLogicalExpression() instanceof AndExpr);
        assertEquals(1, optimizer.getDistributiveLawApplied());
    }
    
    @Test
    void testNoDistributiveLawWithoutCommonFactor() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        // Or(And(a, b), And(c, d)) - no common factor
        List<LogicalExpression> and1 = new ArrayList<>();
        and1.add(new SymbolExpr("a"));
        and1.add(new SymbolExpr("b"));
        
        List<LogicalExpression> and2 = new ArrayList<>();
        and2.add(new SymbolExpr("c"));
        and2.add(new SymbolExpr("d"));
        
        List<LogicalExpression> orArgs = new ArrayList<>();
        orArgs.add(new AndExpr(and1));
        orArgs.add(new AndExpr(and2));
        pc.setLogicalExpression(new OrExpr(orArgs));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        assertTrue(optimized.getLogicalExpression() instanceof OrExpr);
        assertEquals(0, optimizer.getDistributiveLawApplied());
    }
    
    // ========== Constant Expression Tests ==========
    
    @Test
    void testConstantTrue() {
        assertEquals("TRUE", ConstantExpr.TRUE.toString());
        assertTrue(ConstantExpr.TRUE.getValue());
    }
    
    @Test
    void testConstantFalse() {
        assertEquals("FALSE", ConstantExpr.FALSE.toString());
        assertFalse(ConstantExpr.FALSE.getValue());
    }
    
    @Test
    void testConstantSingletons() {
        assertSame(ConstantExpr.TRUE, ConstantExpr.TRUE);
        assertSame(ConstantExpr.FALSE, ConstantExpr.FALSE);
    }
    
    // ========== Statistics Tests ==========
    
    @Test
    void testStatsSummary() {
        String summary = optimizer.getStatsSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("redundancies removed"));
    }
    
    @Test
    void testResetStats() {
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        pc.setMinCount(0);
        optimizer.optimize(pc);
        
        assertTrue(optimizer.getRedundanciesRemoved() > 0);
        
        optimizer.resetStats();
        
        assertEquals(0, optimizer.getRedundanciesRemoved());
    }
    
    // ========== Constant Expression Simplification Tests ==========
    
    @Test
    void testOrWithFalseSimplifiesToOtherArg() {
        // sh:or(A, FALSE) → A
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("symbol_a"));
        args.add(ConstantExpr.FALSE);
        pc.setLogicalExpression(new OrExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // Should simplify to just symbol_a
        assertTrue(optimized.getLogicalExpression() instanceof SymbolExpr,
            "Or(A, FALSE) should simplify to A");
        assertEquals("symbol_a", ((SymbolExpr) optimized.getLogicalExpression()).getName());
        System.out.println("Or(A, FALSE) → " + optimized.getLogicalExpression());
    }
    
    @Test
    void testOrWithMultipleFalseConstants() {
        // sh:or(A, FALSE, FALSE, B) → sh:or(A, B)
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("symbol_a"));
        args.add(ConstantExpr.FALSE);
        args.add(ConstantExpr.FALSE);
        args.add(new SymbolExpr("symbol_b"));
        pc.setLogicalExpression(new OrExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // Should simplify to Or(A, B)
        assertTrue(optimized.getLogicalExpression() instanceof OrExpr,
            "Or(A, FALSE, FALSE, B) should simplify to Or(A, B)");
        OrExpr orExpr = (OrExpr) optimized.getLogicalExpression();
        assertEquals(2, orExpr.getArgs().size());
        System.out.println("Or(A, FALSE, FALSE, B) → " + optimized.getLogicalExpression());
    }
    
    @Test
    void testNotFalseSimplifiesToTrue() {
        // sh:not(FALSE) → TRUE
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        pc.setLogicalExpression(new NotExpr(ConstantExpr.FALSE));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // TRUE simplifies to empty logical constraints (null)
        assertNull(optimized.getLogicalExpression(),
            "Not(FALSE) should simplify to TRUE -> empty constraints");
        System.out.println("Not(FALSE) → (empty)");
    }
    
    @Test
    void testNotTrueSimplifiesToFalse() {
        // sh:not(TRUE) → FALSE
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        pc.setLogicalExpression(new NotExpr(ConstantExpr.TRUE));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // Should simplify to FALSE
        assertTrue(optimized.getLogicalExpression() instanceof ConstantExpr,
            "Not(TRUE) should simplify to FALSE");
        assertFalse(((ConstantExpr) optimized.getLogicalExpression()).getValue(),
            "Result should be FALSE");
        System.out.println("Not(TRUE) → " + optimized.getLogicalExpression());
    }
    
    @Test
    void testAndWithTrueSimplifiesToOtherArg() {
        // sh:and(A, TRUE) → A
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("symbol_a"));
        args.add(ConstantExpr.TRUE);
        pc.setLogicalExpression(new AndExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // Should simplify to just symbol_a
        assertTrue(optimized.getLogicalExpression() instanceof SymbolExpr,
            "And(A, TRUE) should simplify to A");
        assertEquals("symbol_a", ((SymbolExpr) optimized.getLogicalExpression()).getName());
        System.out.println("And(A, TRUE) → " + optimized.getLogicalExpression());
    }
    
    @Test
    void testAndWithFalseSimplifiesToFalse() {
        // sh:and(A, FALSE) → FALSE
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("symbol_a"));
        args.add(ConstantExpr.FALSE);
        pc.setLogicalExpression(new AndExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // Should simplify to FALSE
        assertTrue(optimized.getLogicalExpression() instanceof ConstantExpr,
            "And(A, FALSE) should simplify to FALSE");
        assertFalse(((ConstantExpr) optimized.getLogicalExpression()).getValue(),
            "Result should be FALSE");
        System.out.println("And(A, FALSE) → " + optimized.getLogicalExpression());
    }
    
    @Test
    void testOrWithTrueSimplifiesToTrue() {
        // sh:or(A, TRUE) → TRUE
        PropertyConstraint pc = new PropertyConstraint(
            ResourceFactory.createProperty("http://example.org/prop"));
        
        List<LogicalExpression> args = new ArrayList<>();
        args.add(new SymbolExpr("symbol_a"));
        args.add(ConstantExpr.TRUE);
        pc.setLogicalExpression(new OrExpr(args));
        
        PropertyConstraint optimized = optimizer.optimize(pc);
        
        // TRUE simplifies to empty logical constraints (null)
        assertNull(optimized.getLogicalExpression(),
            "Or(A, TRUE) should simplify to TRUE -> empty constraints");
        System.out.println("Or(A, TRUE) → (empty)");
    }
}
