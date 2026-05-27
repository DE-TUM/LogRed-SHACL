package logic;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Pattern-based expression cache for improved cache hit rate.
 * 
 * Instead of caching specific expressions like "Or(nk_1, cls_2)",
 * we cache expression PATTERNS like "Or($0, $1)" and apply symbol mappings.
 * 
 * This allows expressions with the same structure but different symbols
 * to share cached simplification results.
 * 
 * Example:
 *   Or(nk_1, nk_2) → pattern "Or($0, $1)" with mapping {$0→nk_1, $1→nk_2}
 *   Or(cls_3, cls_4) → same pattern "Or($0, $1)" with mapping {$0→cls_3, $1→cls_4}
 *   
 * If we know Or($0, $1) simplifies to Or($0, $1) (no change), we can apply
 * this result to both expressions without re-running simplification.
 */
public class PatternCache {
    
    // Cache: pattern string -> simplified pattern
    private final ConcurrentHashMap<String, PatternResult> patternCache = new ConcurrentHashMap<>();
    
    // Statistics
    private final AtomicLong patternHits = new AtomicLong(0);
    private final AtomicLong patternMisses = new AtomicLong(0);
    
    /**
     * Represents an expression pattern with placeholder variables.
     */
    public static class ExpressionPattern {
        private final String patternString;
        private final Map<String, String> symbolToPlaceholder;  // nk_1 → $0
        private final Map<String, String> placeholderToSymbol;  // $0 → nk_1
        private final List<String> orderedPlaceholders;         // [$0, $1, ...]
        
        public ExpressionPattern(String patternString, 
                                  Map<String, String> symbolToPlaceholder,
                                  Map<String, String> placeholderToSymbol,
                                  List<String> orderedPlaceholders) {
            this.patternString = patternString;
            this.symbolToPlaceholder = symbolToPlaceholder;
            this.placeholderToSymbol = placeholderToSymbol;
            this.orderedPlaceholders = orderedPlaceholders;
        }
        
        public String getPatternString() { return patternString; }
        public Map<String, String> getSymbolToPlaceholder() { return symbolToPlaceholder; }
        public Map<String, String> getPlaceholderToSymbol() { return placeholderToSymbol; }
        public List<String> getOrderedPlaceholders() { return orderedPlaceholders; }
    }
    
    /**
     * Cached result for a pattern.
     */
    public static class PatternResult {
        private final LogicalExpression simplifiedPattern;
        private final boolean changed;  // Whether simplification changed the expression
        
        public PatternResult(LogicalExpression simplifiedPattern, boolean changed) {
            this.simplifiedPattern = simplifiedPattern;
            this.changed = changed;
        }
        
        public LogicalExpression getSimplifiedPattern() { return simplifiedPattern; }
        public boolean isChanged() { return changed; }
    }
    
    /**
     * Extract a pattern from an expression.
     * 
     * Strategy:
     * 1. First, normalize the expression (sort And/Or operands recursively)
     * 2. Then, traverse in DFS order and assign placeholders by encounter order
     * 
     * This ensures structurally equivalent expressions produce the same pattern,
     * regardless of original symbol names.
     */
    public ExpressionPattern extractPattern(LogicalExpression expr) {
        // Step 1: Normalize the expression structure (sort operands)
        LogicalExpression normalized = normalizeExpression(expr);
        
        // Step 2: Collect symbols in DFS traversal order of the normalized expression
        List<String> symbolsInOrder = new ArrayList<>();
        collectSymbolsInOrder(normalized, symbolsInOrder);
        
        // Create mappings based on DFS encounter order
        Map<String, String> symbolToPlaceholder = new LinkedHashMap<>();
        Map<String, String> placeholderToSymbol = new LinkedHashMap<>();
        List<String> orderedPlaceholders = new ArrayList<>();
        
        int idx = 0;
        for (String symbol : symbolsInOrder) {
            if (!symbolToPlaceholder.containsKey(symbol)) {
                String placeholder = "$" + idx;
                symbolToPlaceholder.put(symbol, placeholder);
                placeholderToSymbol.put(placeholder, symbol);
                orderedPlaceholders.add(placeholder);
                idx++;
            }
        }
        
        // Convert normalized expression to pattern string
        String patternString = toPatternStringSimple(normalized, symbolToPlaceholder);
        
        return new ExpressionPattern(patternString, symbolToPlaceholder, 
                                      placeholderToSymbol, orderedPlaceholders);
    }
    
    /**
     * Normalize expression by sorting And/Or operands recursively.
     * This uses a structural string representation for sorting.
     */
    private LogicalExpression normalizeExpression(LogicalExpression expr) {
        if (expr instanceof SymbolExpr) {
            return expr;
        } else if (expr instanceof NotExpr not) {
            return new NotExpr(normalizeExpression(not.getArg()));
        } else if (expr instanceof AndExpr and) {
            List<LogicalExpression> normalized = and.getArgs().stream()
                .map(this::normalizeExpression)
                .sorted(Comparator.comparing(this::structuralString))
                .collect(Collectors.toList());
            return new AndExpr(normalized);
        } else if (expr instanceof OrExpr or) {
            List<LogicalExpression> normalized = or.getArgs().stream()
                .map(this::normalizeExpression)
                .sorted(Comparator.comparing(this::structuralString))
                .collect(Collectors.toList());
            return new OrExpr(normalized);
        }
        return expr;
    }
    
    /**
     * Generate a structural string for sorting.
     * Uses generic placeholders so that symbol names don't affect ordering.
     */
    private String structuralString(LogicalExpression expr) {
        if (expr instanceof SymbolExpr) {
            return "S";  // All symbols are equivalent for structural comparison
        } else if (expr instanceof NotExpr not) {
            return "N(" + structuralString(not.getArg()) + ")";
        } else if (expr instanceof AndExpr and) {
            List<String> parts = and.getArgs().stream()
                .map(this::structuralString)
                .sorted()
                .collect(Collectors.toList());
            return "A(" + String.join(",", parts) + ")";
        } else if (expr instanceof OrExpr or) {
            List<String> parts = or.getArgs().stream()
                .map(this::structuralString)
                .sorted()
                .collect(Collectors.toList());
            return "O(" + String.join(",", parts) + ")";
        }
        return "?";
    }
    
    /**
     * Collect symbols in DFS traversal order.
     */
    private void collectSymbolsInOrder(LogicalExpression expr, List<String> symbols) {
        if (expr instanceof SymbolExpr sym) {
            symbols.add(sym.getName());
        } else if (expr instanceof NotExpr not) {
            collectSymbolsInOrder(not.getArg(), symbols);
        } else if (expr instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) {
                collectSymbolsInOrder(arg, symbols);
            }
        } else if (expr instanceof OrExpr or) {
            for (LogicalExpression arg : or.getArgs()) {
                collectSymbolsInOrder(arg, symbols);
            }
        }
    }
    
    /**
     * Convert normalized expression to pattern string (no re-sorting needed).
     */
    private String toPatternStringSimple(LogicalExpression expr, Map<String, String> symbolToPlaceholder) {
        if (expr instanceof SymbolExpr sym) {
            return symbolToPlaceholder.getOrDefault(sym.getName(), sym.getName());
        } else if (expr instanceof NotExpr not) {
            return "Not(" + toPatternStringSimple(not.getArg(), symbolToPlaceholder) + ")";
        } else if (expr instanceof AndExpr and) {
            List<String> argPatterns = and.getArgs().stream()
                .map(arg -> toPatternStringSimple(arg, symbolToPlaceholder))
                .collect(Collectors.toList());
            return "And(" + String.join(",", argPatterns) + ")";
        } else if (expr instanceof OrExpr or) {
            List<String> argPatterns = or.getArgs().stream()
                .map(arg -> toPatternStringSimple(arg, symbolToPlaceholder))
                .collect(Collectors.toList());
            return "Or(" + String.join(",", argPatterns) + ")";
        }
        return expr.toString();
    }
    
    /**
     * Check cache for a pattern.
     */
    public PatternResult getCached(String patternString) {
        PatternResult result = patternCache.get(patternString);
        if (result != null) {
            patternHits.incrementAndGet();
        } else {
            patternMisses.incrementAndGet();
        }
        return result;
    }
    
    /**
     * Store a simplified pattern in cache.
     */
    public void cache(String patternString, LogicalExpression simplifiedPattern, boolean changed) {
        patternCache.put(patternString, new PatternResult(simplifiedPattern, changed));
    }
    
    /**
     * Apply symbol mapping to a pattern expression to get concrete expression.
     */
    public LogicalExpression applyMapping(LogicalExpression patternExpr, 
                                          Map<String, String> placeholderToSymbol) {
        if (patternExpr instanceof SymbolExpr sym) {
            String name = sym.getName();
            // If it's a placeholder, replace with actual symbol
            if (name.startsWith("$")) {
                String actualSymbol = placeholderToSymbol.get(name);
                return actualSymbol != null ? new SymbolExpr(actualSymbol) : patternExpr;
            }
            return patternExpr;
        } else if (patternExpr instanceof NotExpr not) {
            return new NotExpr(applyMapping(not.getArg(), placeholderToSymbol));
        } else if (patternExpr instanceof AndExpr and) {
            List<LogicalExpression> mappedArgs = and.getArgs().stream()
                .map(arg -> applyMapping(arg, placeholderToSymbol))
                .collect(Collectors.toList());
            return new AndExpr(mappedArgs);
        } else if (patternExpr instanceof OrExpr or) {
            List<LogicalExpression> mappedArgs = or.getArgs().stream()
                .map(arg -> applyMapping(arg, placeholderToSymbol))
                .collect(Collectors.toList());
            return new OrExpr(mappedArgs);
        }
        return patternExpr;
    }
    
    /**
     * Convert concrete expression to pattern expression (with placeholder symbols).
     */
    public LogicalExpression toPatternExpression(LogicalExpression expr, 
                                                  Map<String, String> symbolToPlaceholder) {
        if (expr instanceof SymbolExpr sym) {
            String placeholder = symbolToPlaceholder.get(sym.getName());
            return placeholder != null ? new SymbolExpr(placeholder) : expr;
        } else if (expr instanceof NotExpr not) {
            return new NotExpr(toPatternExpression(not.getArg(), symbolToPlaceholder));
        } else if (expr instanceof AndExpr and) {
            List<LogicalExpression> patternArgs = and.getArgs().stream()
                .map(arg -> toPatternExpression(arg, symbolToPlaceholder))
                .collect(Collectors.toList());
            return new AndExpr(patternArgs);
        } else if (expr instanceof OrExpr or) {
            List<LogicalExpression> patternArgs = or.getArgs().stream()
                .map(arg -> toPatternExpression(arg, symbolToPlaceholder))
                .collect(Collectors.toList());
            return new OrExpr(patternArgs);
        }
        return expr;
    }
    
    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        return new CacheStats(
            patternHits.get(),
            patternMisses.get(),
            patternCache.size()
        );
    }
    
    /**
     * Clear the cache.
     */
    public void clear() {
        patternCache.clear();
        patternHits.set(0);
        patternMisses.set(0);
    }
    
    public record CacheStats(long hits, long misses, int size) {
        public double hitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("PatternCache[hits=%d, misses=%d, hitRate=%.2f%%, patterns=%d]",
                hits, misses, hitRate() * 100, size);
        }
    }
}
