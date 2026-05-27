package logic;

import org.apache.jena.rdf.model.RDFNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the mapping between constraint symbols and their actual constraint values.
 * Similar to Python's symbol_dict and value_to_symbol mappings.
 * 
 * Each unique constraint (type + value) gets assigned a unique symbol name.
 * This allows logical expressions to operate on symbols while preserving
 * the ability to serialize back to SHACL constraints.
 * 
 * Thread-safe for parallel processing.
 */
public class SymbolTable {
    private static final Logger log = LoggerFactory.getLogger(SymbolTable.class);
    
    /**
     * Represents a constraint type-value pair.
     */
    public record ConstraintInfo(String constraintType, RDFNode constraintValue) {}
    
    // Symbol name -> Constraint info (thread-safe)
    private final ConcurrentHashMap<String, ConstraintInfo> symbolDict = new ConcurrentHashMap<>();
    
    // Constraint key -> Symbol name (for deduplication, thread-safe)
    private final ConcurrentHashMap<String, String> valueToSymbol = new ConcurrentHashMap<>();
    
    // Counter for generating unique symbol names
    private final AtomicInteger symbolCounter = new AtomicInteger(0);
    
    // Inverted index: constraint type -> set of symbols (thread-safe)
    private final ConcurrentHashMap<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    
    /**
     * Assign a symbol for a constraint. Returns existing symbol if the same constraint
     * was already assigned.
     * 
     * Thread-safe: uses computeIfAbsent to ensure atomic check-and-create.
     * 
     * @param constraintType The SHACL constraint type (e.g., "sh:nodeKind", "sh:class")
     * @param constraintValue The constraint value
     * @return The symbol name
     */
    public String assignSymbol(String constraintType, RDFNode constraintValue) {
        // Create a canonical key for this constraint
        String key = createKey(constraintType, constraintValue);
        
        // Thread-safe: computeIfAbsent ensures only one symbol is created per key
        return valueToSymbol.computeIfAbsent(key, k -> {
            // Determine prefix based on constraint type
            String prefix = getPrefix(constraintType);
            
            // Generate new symbol
            int counter = symbolCounter.incrementAndGet();
            String symbol = prefix + "_" + counter;
            
            // Store constraint info
            symbolDict.put(symbol, new ConstraintInfo(constraintType, constraintValue));
            
            // Update inverted index (thread-safe)
            invertedIndex.computeIfAbsent(constraintType, ct -> ConcurrentHashMap.newKeySet()).add(symbol);
            
            log.debug("Assigned symbol {} for {}={}", symbol, constraintType, constraintValue);
            return symbol;
        });
    }
    
    /**
     * Create a LogicalExpression symbol for a constraint.
     */
    public LogicalExpression createSymbolExpr(String constraintType, RDFNode constraintValue) {
        String symbolName = assignSymbol(constraintType, constraintValue);
        return new SymbolExpr(symbolName);
    }
    
    /**
     * Get constraint info for a symbol.
     */
    public ConstraintInfo getConstraintInfo(String symbolName) {
        return symbolDict.get(symbolName);
    }
    
    /**
     * Get all symbols of a specific constraint type.
     */
    public Set<String> getSymbolsOfType(String constraintType) {
        return invertedIndex.getOrDefault(constraintType, Collections.emptySet());
    }
    
    /**
     * Check if a symbol exists.
     */
    public boolean hasSymbol(String symbolName) {
        return symbolDict.containsKey(symbolName);
    }
    
    /**
     * Get all symbol names.
     */
    public Set<String> getAllSymbols() {
        return Collections.unmodifiableSet(symbolDict.keySet());
    }
    
    /**
     * Get the symbol dict for serialization.
     */
    public Map<String, ConstraintInfo> getSymbolDict() {
        return Collections.unmodifiableMap(symbolDict);
    }
    
    private String createKey(String constraintType, RDFNode constraintValue) {
        String valueStr;
        if (constraintValue == null) {
            valueStr = "null";
        } else if (constraintValue.isURIResource()) {
            valueStr = constraintValue.asResource().getURI();
        } else if (constraintValue.isLiteral()) {
            valueStr = constraintValue.asLiteral().toString();
        } else {
            valueStr = constraintValue.toString();
        }
        return constraintType + "::" + valueStr;
    }
    
    private String getPrefix(String constraintType) {
        if (constraintType == null) return "x";
        
        return switch (constraintType) {
            case "sh:NodeShape" -> "shape";
            case "sh:qualifiedValueShape" -> "qv";
            case "sh:xone" -> "xv";
            case "sh:nodeKind" -> "nk";
            case "sh:class" -> "cls";
            case "sh:datatype" -> "dt";
            case "sh:in" -> "in";
            case "sh:minCount" -> "minc";
            case "sh:maxCount" -> "maxc";
            case "sh:minLength" -> "minl";
            case "sh:maxLength" -> "maxl";
            case "sh:pattern" -> "pat";
            case "sh:flags" -> "flg";
            case "sh:hasValue" -> "hv";
            case "sh:node" -> "node";
            case "sh:equals" -> "eq";
            case "sh:disjoint" -> "disj";
            case "sh:lessThan" -> "lt";
            case "sh:lessThanOrEquals" -> "lte";
            case "sh:minInclusive" -> "mini";
            case "sh:maxInclusive" -> "maxi";
            case "sh:minExclusive" -> "mine";
            case "sh:maxExclusive" -> "maxe";
            case "sh:uniqueLang" -> "ul";
            default -> "x";
        };
    }
    
    @Override
    public String toString() {
        return "SymbolTable{symbols=" + symbolDict.size() + "}";
    }
}
