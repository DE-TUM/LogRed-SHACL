package logic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Test nested expressions to verify pattern cache doesn't affect semantics.
 * 
 * Key question: When we have nested expressions like Or(And(A,B), C),
 * does the sorting in pattern normalization affect the logical result?
 */
public class NestedExpressionPatternTest {
    
    @Test
    @DisplayName("Test that nested expressions with same structure match the same pattern")
    void testNestedExpressionPatternMatching() {
        PatternCache cache = new PatternCache();
        
        // Expression 1: Or(And(A, B), C)
        LogicalExpression expr1 = new OrExpr(List.of(
            new AndExpr(List.of(new SymbolExpr("A"), new SymbolExpr("B"))),
            new SymbolExpr("C")
        ));
        
        // Expression 2: Or(And(X, Y), Z) - same structure, different symbols
        LogicalExpression expr2 = new OrExpr(List.of(
            new AndExpr(List.of(new SymbolExpr("X"), new SymbolExpr("Y"))),
            new SymbolExpr("Z")
        ));
        
        // Expression 3: Or(C, And(A, B)) - reordered but logically equivalent
        LogicalExpression expr3 = new OrExpr(List.of(
            new SymbolExpr("C"),
            new AndExpr(List.of(new SymbolExpr("A"), new SymbolExpr("B")))
        ));
        
        PatternCache.ExpressionPattern pattern1 = cache.extractPattern(expr1);
        PatternCache.ExpressionPattern pattern2 = cache.extractPattern(expr2);
        PatternCache.ExpressionPattern pattern3 = cache.extractPattern(expr3);
        
        System.out.println("Expression 1: " + expr1);
        System.out.println("  Pattern: " + pattern1.getPatternString());
        System.out.println("  Mapping: " + pattern1.getPlaceholderToSymbol());
        
        System.out.println("\nExpression 2: " + expr2);
        System.out.println("  Pattern: " + pattern2.getPatternString());
        System.out.println("  Mapping: " + pattern2.getPlaceholderToSymbol());
        
        System.out.println("\nExpression 3: " + expr3);
        System.out.println("  Pattern: " + pattern3.getPatternString());
        System.out.println("  Mapping: " + pattern3.getPlaceholderToSymbol());
        
        // expr1 and expr2 should have the same pattern
        assertEquals(pattern1.getPatternString(), pattern2.getPatternString(),
            "Same-structure expressions should have same pattern");
        
        // expr1 and expr3 should also have the same pattern (due to sorting)
        assertEquals(pattern1.getPatternString(), pattern3.getPatternString(),
            "Reordered OR expressions should have same pattern (commutative)");
    }
    
    @Test
    @DisplayName("Test deep nesting: Or(And(Or(A,B), C), D)")
    void testDeepNestedExpressions() {
        PatternCache cache = new PatternCache();
        
        // Deep nested: Or(And(Or(A,B), C), D)
        LogicalExpression deep1 = new OrExpr(List.of(
            new AndExpr(List.of(
                new OrExpr(List.of(new SymbolExpr("A"), new SymbolExpr("B"))),
                new SymbolExpr("C")
            )),
            new SymbolExpr("D")
        ));
        
        // Same structure: Or(And(Or(X,Y), Z), W)
        LogicalExpression deep2 = new OrExpr(List.of(
            new AndExpr(List.of(
                new OrExpr(List.of(new SymbolExpr("X"), new SymbolExpr("Y"))),
                new SymbolExpr("Z")
            )),
            new SymbolExpr("W")
        ));
        
        PatternCache.ExpressionPattern pattern1 = cache.extractPattern(deep1);
        PatternCache.ExpressionPattern pattern2 = cache.extractPattern(deep2);
        
        System.out.println("\nDeep nested 1: " + deep1);
        System.out.println("  Pattern: " + pattern1.getPatternString());
        
        System.out.println("\nDeep nested 2: " + deep2);
        System.out.println("  Pattern: " + pattern2.getPatternString());
        
        assertEquals(pattern1.getPatternString(), pattern2.getPatternString(),
            "Deep nested expressions with same structure should match");
    }
    
    @Test
    @DisplayName("Verify that pattern cache returns correct results for nested expressions")
    void testNestedExpressionSimplification() {
        SymbolTable symbolTable = new SymbolTable();
        LogicSimplifier simplifier = new LogicSimplifier(symbolTable);
        simplifier.setPatternCacheEnabled(true);

        
        // First: And(A, Or(A, B)) - should simplify to A (absorption law)
        LogicalExpression expr1 = new AndExpr(List.of(
            new SymbolExpr("person_1"),
            new OrExpr(List.of(new SymbolExpr("person_1"), new SymbolExpr("agent_2")))
        ));
        
        LogicalExpression result1 = simplifier.simplify(expr1);
        System.out.println("\nExpr1: " + expr1);
        System.out.println("Result1: " + result1);
        
        // Second: And(X, Or(X, Y)) - same structure, should hit cache
        LogicalExpression expr2 = new AndExpr(List.of(
            new SymbolExpr("place_3"),
            new OrExpr(List.of(new SymbolExpr("place_3"), new SymbolExpr("location_4")))
        ));
        
        LogicalExpression result2 = simplifier.simplify(expr2);
        System.out.println("\nExpr2: " + expr2);
        System.out.println("Result2: " + result2);
        
        // Verify results are correct (both should be just the first symbol)
        assertEquals("person_1", result1.toString(), 
            "And(A, Or(A, B)) should simplify to A");
        assertEquals("place_3", result2.toString(),
            "And(X, Or(X, Y)) should simplify to X");
        
        // Verify cache was hit
        var stats = simplifier.getCacheStats();
        System.out.println("\nCache stats: hits=" + stats.patternHits() + ", misses=" + stats.misses());
        assertTrue(stats.patternHits() > 0, "Pattern cache should have hits");
    }
    
    @Test
    @DisplayName("Test that different structures don't incorrectly match")
    void testDifferentStructuresDontMatch() {
        PatternCache cache = new PatternCache();
        
        // Structure 1: Or(And(A, B), C) - And inside Or
        LogicalExpression struct1 = new OrExpr(List.of(
            new AndExpr(List.of(new SymbolExpr("A"), new SymbolExpr("B"))),
            new SymbolExpr("C")
        ));
        
        // Structure 2: And(Or(A, B), C) - Or inside And (different!)
        LogicalExpression struct2 = new AndExpr(List.of(
            new OrExpr(List.of(new SymbolExpr("A"), new SymbolExpr("B"))),
            new SymbolExpr("C")
        ));
        
        PatternCache.ExpressionPattern pattern1 = cache.extractPattern(struct1);
        PatternCache.ExpressionPattern pattern2 = cache.extractPattern(struct2);
        
        System.out.println("\nStructure 1: " + struct1);
        System.out.println("  Pattern: " + pattern1.getPatternString());
        
        System.out.println("\nStructure 2: " + struct2);
        System.out.println("  Pattern: " + pattern2.getPatternString());
        
        assertNotEquals(pattern1.getPatternString(), pattern2.getPatternString(),
            "Different structures should have different patterns");
    }
    
    @Test
    @DisplayName("Verify semantic correctness with complex nested expression")
    void testComplexNestedSemantics() {
        SymbolTable symbolTable = new SymbolTable();
        
        // Test with exact cache
        LogicSimplifier exactSimplifier = new LogicSimplifier(symbolTable);
        exactSimplifier.setPatternCacheEnabled(false);
        
        // Test with pattern cache  
        LogicSimplifier patternSimplifier = new LogicSimplifier(new SymbolTable());
        patternSimplifier.setPatternCacheEnabled(true);

        
        // Complex expression: Or(And(A, Not(A)), B) = B (And(A, Not(A)) is always false)
        LogicalExpression complex = new OrExpr(List.of(
            new AndExpr(List.of(
                new SymbolExpr("contradiction_x"),
                new NotExpr(new SymbolExpr("contradiction_x"))
            )),
            new SymbolExpr("result_y")
        ));
        
        LogicalExpression exactResult = exactSimplifier.simplify(complex);
        LogicalExpression patternResult = patternSimplifier.simplify(complex);
        
        System.out.println("\nComplex expression: " + complex);
        System.out.println("Exact cache result: " + exactResult);
        System.out.println("Pattern cache result: " + patternResult);
        
        // Both should produce the same semantic result
        assertEquals(exactResult.toString(), patternResult.toString(),
            "Exact and pattern cache should produce same result for complex expressions");
        
        // Result should be simplified to just "result_y" since And(A, Not(A)) = false
        assertEquals("result_y", patternResult.toString(),
            "Or(And(A, Not(A)), B) should simplify to B");
    }
    
    @Test
    @DisplayName("Test multiple levels of nesting with different orderings")
    void testMultipleLevelsWithReordering() {
        PatternCache cache = new PatternCache();
        
        // Or(And(A, B), And(C, D))
        LogicalExpression expr1 = new OrExpr(List.of(
            new AndExpr(List.of(new SymbolExpr("A"), new SymbolExpr("B"))),
            new AndExpr(List.of(new SymbolExpr("C"), new SymbolExpr("D")))
        ));
        
        // Or(And(D, C), And(B, A)) - reordered at multiple levels
        LogicalExpression expr2 = new OrExpr(List.of(
            new AndExpr(List.of(new SymbolExpr("D"), new SymbolExpr("C"))),
            new AndExpr(List.of(new SymbolExpr("B"), new SymbolExpr("A")))
        ));
        
        PatternCache.ExpressionPattern pattern1 = cache.extractPattern(expr1);
        PatternCache.ExpressionPattern pattern2 = cache.extractPattern(expr2);
        
        System.out.println("\nExpr1: " + expr1);
        System.out.println("  Pattern: " + pattern1.getPatternString());
        System.out.println("  Mapping: " + pattern1.getPlaceholderToSymbol());
        
        System.out.println("\nExpr2: " + expr2);
        System.out.println("  Pattern: " + pattern2.getPatternString());
        System.out.println("  Mapping: " + pattern2.getPlaceholderToSymbol());
        
        // Due to sorting, both should normalize to the same pattern
        assertEquals(pattern1.getPatternString(), pattern2.getPatternString(),
            "Reordered expressions should have same pattern due to commutative normalization");
    }
}
