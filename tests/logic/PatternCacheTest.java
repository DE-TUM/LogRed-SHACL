package logic;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PatternCache - pattern-based expression caching.
 */
class PatternCacheTest {
    
    private PatternCache cache;
    
    @BeforeEach
    void setUp() {
        cache = new PatternCache();
    }
    
    @Test
    void testExtractPattern_SimpleOr() {
        // Or(nk_1, cls_2) should extract to Or($0,$1)
        OrExpr expr = new OrExpr(new SymbolExpr("nk_1"), new SymbolExpr("cls_2"));
        var pattern = cache.extractPattern(expr);
        
        assertEquals("Or($0,$1)", pattern.getPatternString());
    }
    
    @Test
    void testExtractPattern_NestedAnd() {
        // And(Or(a, b), c) - note: arguments get sorted
        AndExpr expr = new AndExpr(
            new OrExpr(new SymbolExpr("a"), new SymbolExpr("b")),
            new SymbolExpr("c")
        );
        var pattern = cache.extractPattern(expr);
        
        // Arguments are sorted alphabetically
        assertTrue(pattern.getPatternString().contains("And("));
        assertTrue(pattern.getPatternString().contains("Or("));
    }
    
    @Test
    void testExtractPattern_Not() {
        // Not(a) -> Not($0)
        NotExpr expr = new NotExpr(new SymbolExpr("a"));
        var pattern = cache.extractPattern(expr);
        
        assertEquals("Not($0)", pattern.getPatternString());
    }
    
    @Test
    void testSamePatternDifferentSymbols() {
        // Two expressions with same structure but different symbols
        OrExpr expr1 = new OrExpr(new SymbolExpr("nk_1"), new SymbolExpr("nk_2"));
        OrExpr expr2 = new OrExpr(new SymbolExpr("cls_3"), new SymbolExpr("cls_4"));
        
        var pattern1 = cache.extractPattern(expr1);
        var pattern2 = cache.extractPattern(expr2);
        
        assertEquals(pattern1.getPatternString(), pattern2.getPatternString(), 
            "Same structure should produce same pattern");
    }
    
    @Test
    void testDifferentStructures() {
        // Two expressions with different structures
        OrExpr expr1 = new OrExpr(new SymbolExpr("a"), new SymbolExpr("b"));
        AndExpr expr2 = new AndExpr(new SymbolExpr("a"), new SymbolExpr("b"));
        
        var pattern1 = cache.extractPattern(expr1);
        var pattern2 = cache.extractPattern(expr2);
        
        assertNotEquals(pattern1.getPatternString(), pattern2.getPatternString(), 
            "Different structures should have different patterns");
    }
    
    @Test
    void testApplyMapping_SimpleOr() {
        // Given pattern with mapping, should reconstruct expression
        OrExpr original = new OrExpr(new SymbolExpr("nk_1"), new SymbolExpr("cls_2"));
        var pattern = cache.extractPattern(original);
        
        // Create a pattern expression with placeholders
        LogicalExpression patternExpr = new SymbolExpr("$1"); // Just the second placeholder
        
        // Apply mapping to get final result
        LogicalExpression result = cache.applyMapping(patternExpr, pattern.getPlaceholderToSymbol());
        
        assertTrue(result instanceof SymbolExpr);
        // The mapping maps placeholders to original symbols in sorted order
    }
    
    @Test
    void testCachePutAndGet() {
        // Put a pattern result into cache
        String patternString = "Or($0,$1)";
        LogicalExpression simplifiedExpr = new OrExpr(new SymbolExpr("$0"), new SymbolExpr("$1"));
        
        cache.cache(patternString, simplifiedExpr, false);
        
        // Retrieve it
        var result = cache.getCached(patternString);
        
        assertNotNull(result);
        assertFalse(result.isChanged());
    }
    
    @Test
    void testCacheMiss() {
        var result = cache.getCached("NonExistent($0)");
        assertNull(result);
    }
    
    @Test
    void testExtractMapping() {
        OrExpr expr = new OrExpr(new SymbolExpr("alpha"), new SymbolExpr("beta"));
        var pattern = cache.extractPattern(expr);
        
        var symbolToPlaceholder = pattern.getSymbolToPlaceholder();
        var placeholderToSymbol = pattern.getPlaceholderToSymbol();
        
        assertEquals(2, symbolToPlaceholder.size());
        assertEquals(2, placeholderToSymbol.size());
        assertTrue(symbolToPlaceholder.containsKey("alpha"));
        assertTrue(symbolToPlaceholder.containsKey("beta"));
    }
    
    @Test
    void testApplyMappingComplex() {
        // Test applying mapping to a complex pattern
        var placeholderToSymbol = java.util.Map.of("$0", "nk_1", "$1", "cls_2");
        
        LogicalExpression patternExpr = new AndExpr(new SymbolExpr("$0"), new SymbolExpr("$1"));
        LogicalExpression result = cache.applyMapping(patternExpr, placeholderToSymbol);
        
        assertTrue(result instanceof AndExpr);
        AndExpr andExpr = (AndExpr) result;
        assertEquals(2, andExpr.getArgs().size());
    }
    
    @Test
    void testCacheClear() {
        cache.cache("Pattern1", new SymbolExpr("$0"), false);
        cache.cache("Pattern2", new SymbolExpr("$1"), false);
        
        var stats = cache.getStats();
        assertEquals(2, stats.size());
        
        cache.clear();
        
        stats = cache.getStats();
        assertEquals(0, stats.size());
    }
    
    @Test
    void testCacheStats() {
        cache.cache("Pattern1", new SymbolExpr("$0"), false);
        cache.getCached("Pattern1"); // hit
        cache.getCached("Pattern2"); // miss
        
        var stats = cache.getStats();
        
        assertEquals(1, stats.size());
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
    }
    
    @Test
    void testToPatternExpression() {
        // Convert concrete expression to pattern expression
        OrExpr concrete = new OrExpr(new SymbolExpr("alpha"), new SymbolExpr("beta"));
        var pattern = cache.extractPattern(concrete);
        
        LogicalExpression patternExpr = cache.toPatternExpression(concrete, pattern.getSymbolToPlaceholder());
        
        // The result should use placeholders
        assertTrue(patternExpr instanceof OrExpr);
    }
    
    @Test
    void testHitRateCalculation() {
        cache.cache("Pattern1", new SymbolExpr("$0"), false);
        
        // 3 hits
        cache.getCached("Pattern1");
        cache.getCached("Pattern1");
        cache.getCached("Pattern1");
        
        // 1 miss
        cache.getCached("Pattern2");
        
        var stats = cache.getStats();
        assertEquals(3, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(0.75, stats.hitRate(), 0.001);
    }
}
