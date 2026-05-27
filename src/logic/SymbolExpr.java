package logic;

import java.util.*;

/**
 * Represents a symbol (atomic constraint) in a logical expression.
 * Each symbol has a unique name that maps to a constraint in the SymbolTable.
 */
public class SymbolExpr extends LogicalExpression {
    private final String name;
    
    public SymbolExpr(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
    
    @Override
    public Set<String> getFreeSymbols() {
        return Collections.singleton(name);
    }
    
    @Override
    public LogicalExpression copy() {
        return new SymbolExpr(name);
    }
    
    @Override
    public boolean isEquivalent(LogicalExpression other) {
        if (other instanceof SymbolExpr sym) {
            return name.equals(sym.name);
        }
        return false;
    }
    
    @Override
    public String toString() {
        return name;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SymbolExpr that = (SymbolExpr) o;
        return Objects.equals(name, that.name);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
