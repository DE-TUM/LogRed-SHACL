package logic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Test that pattern cache correctly maps results back to original variables.
 */
public class PatternCacheMappingTest {
    
    @Test
    @DisplayName("Pattern cache should map simplified results back to original symbols")
    void testPatternMappingBackToOriginal() {
        PatternCache cache = new PatternCache();
        
        // Expression 1: Or(nk_1, cls_2)
        LogicalExpression expr1 = new OrExpr(List.of(
            new SymbolExpr("nk_1"),
            new SymbolExpr("cls_2")
        ));
        
        // Extract pattern from expr1
        PatternCache.ExpressionPattern pattern1 = cache.extractPattern(expr1);
        System.out.println("Expression 1: " + expr1);
        System.out.println("Pattern 1: " + pattern1.getPatternString());
        System.out.println("Mapping: " + pattern1.getPlaceholderToSymbol());
        
        // Simulate: simplified pattern is Or($0, $1) (no change)
        LogicalExpression simplifiedPattern = new OrExpr(List.of(
            new SymbolExpr("$0"),
            new SymbolExpr("$1")
        ));
        cache.cache(pattern1.getPatternString(), simplifiedPattern, false);
        
        // Expression 2: Or(cls_3, dt_4) - same structure, different symbols
        LogicalExpression expr2 = new OrExpr(List.of(
            new SymbolExpr("cls_3"),
            new SymbolExpr("dt_4")
        ));
        
        // Extract pattern from expr2
        PatternCache.ExpressionPattern pattern2 = cache.extractPattern(expr2);
        System.out.println("\nExpression 2: " + expr2);
        System.out.println("Pattern 2: " + pattern2.getPatternString());
        System.out.println("Mapping: " + pattern2.getPlaceholderToSymbol());
        
        // Same pattern! Should hit cache
        assertEquals(pattern1.getPatternString(), pattern2.getPatternString());
        
        // Get cached result and apply mapping for expr2
        PatternCache.PatternResult cached = cache.getCached(pattern2.getPatternString());
        assertNotNull(cached);
        
        LogicalExpression mappedResult = cache.applyMapping(
            cached.getSimplifiedPattern(),
            pattern2.getPlaceholderToSymbol()
        );
        
        System.out.println("\nCached pattern result: " + cached.getSimplifiedPattern());
        System.out.println("Mapped back to expr2 symbols: " + mappedResult);
        
        // Verify the result uses expr2's original symbols
        assertTrue(mappedResult.toString().contains("cls_3"));
        assertTrue(mappedResult.toString().contains("dt_4"));
        assertFalse(mappedResult.toString().contains("$0"));
        assertFalse(mappedResult.toString().contains("$1"));
        assertFalse(mappedResult.toString().contains("nk_1"));
        assertFalse(mappedResult.toString().contains("cls_2"));
    }
    
    @Test
    @DisplayName("Pattern cache should correctly map simplified And expressions")
    void testPatternMappingForSimplifiedExpression() {
        PatternCache cache = new PatternCache();
        
        // Expression: And(A, Or(A, B)) should simplify to just A (absorption)
        // But we test the mapping, not the simplification
        
        LogicalExpression expr1 = new AndExpr(List.of(
            new SymbolExpr("x"),
            new OrExpr(List.of(new SymbolExpr("x"), new SymbolExpr("y")))
        ));
        
        PatternCache.ExpressionPattern pattern1 = cache.extractPattern(expr1);
        System.out.println("\nOriginal: " + expr1);
        System.out.println("Pattern: " + pattern1.getPatternString());
        
        // Simulate: And($0, Or($0, $1)) simplifies to $0
        LogicalExpression simplifiedPattern = new SymbolExpr("$0");
        cache.cache(pattern1.getPatternString(), simplifiedPattern, true);
        
        // Now try with different symbols
        LogicalExpression expr2 = new AndExpr(List.of(
            new SymbolExpr("alpha"),
            new OrExpr(List.of(new SymbolExpr("alpha"), new SymbolExpr("beta")))
        ));
        
        PatternCache.ExpressionPattern pattern2 = cache.extractPattern(expr2);
        System.out.println("\nSecond expression: " + expr2);
        System.out.println("Pattern: " + pattern2.getPatternString());
        
        PatternCache.PatternResult cached = cache.getCached(pattern2.getPatternString());
        assertNotNull(cached);
        
        LogicalExpression mappedResult = cache.applyMapping(
            cached.getSimplifiedPattern(),
            pattern2.getPlaceholderToSymbol()
        );
        
        System.out.println("Simplified pattern: " + cached.getSimplifiedPattern());
        System.out.println("Mapped result: " + mappedResult);
        
        // Result should be "alpha" (the $0 symbol from expr2's mapping)
        assertEquals("alpha", mappedResult.toString());
    }
    
    @Test
    @DisplayName("Full simplifier test: verify pattern cache returns correctly mapped results")
    void testLogicSimplifierWithPatternCache() {
        SymbolTable symbolTable = new SymbolTable();
        LogicSimplifier simplifier = new LogicSimplifier(symbolTable);
        simplifier.setPatternCacheEnabled(true);

        
        // First expression - will cause cache miss and simplification
        LogicalExpression expr1 = new OrExpr(List.of(
            new SymbolExpr("nk_Person"),
            new SymbolExpr("cls_Agent")
        ));
        
        LogicalExpression result1 = simplifier.simplify(expr1);
        System.out.println("\nExpr1: " + expr1 + " -> " + result1);
        
        // Second expression - same pattern, should hit cache
        LogicalExpression expr2 = new OrExpr(List.of(
            new SymbolExpr("nk_Place"),
            new SymbolExpr("cls_Location")
        ));
        
        LogicalExpression result2 = simplifier.simplify(expr2);
        System.out.println("Expr2: " + expr2 + " -> " + result2);
        
        // Verify result2 uses expr2's symbols, not expr1's
        String result2Str = result2.toString();
        assertTrue(result2Str.contains("nk_Place") || result2Str.contains("cls_Location"),
            "Result should contain expr2's symbols: " + result2Str);
        assertFalse(result2Str.contains("nk_Person"),
            "Result should NOT contain expr1's symbols: " + result2Str);
        assertFalse(result2Str.contains("cls_Agent"),
            "Result should NOT contain expr1's symbols: " + result2Str);
        assertFalse(result2Str.contains("$"),
            "Result should NOT contain placeholders: " + result2Str);
        
        System.out.println("\nPattern cache correctly maps results back to original symbols!");
    }
}
