package logic;

import java.util.Collections;
import java.util.Set;

/**
 * Represents a constant boolean value (TRUE or FALSE) in logical expressions.
 * Used to represent constraints that are always satisfied or never satisfiable.
 */
public class ConstantExpr extends LogicalExpression {
    public static final ConstantExpr TRUE = new ConstantExpr(true);
    public static final ConstantExpr FALSE = new ConstantExpr(false);
    
    private final boolean value;
    
    private ConstantExpr(boolean value) {
        this.value = value;
    }
    
    public boolean getValue() {
        return value;
    }
    
    @Override
    public Set<String> getFreeSymbols() {
        return Collections.emptySet();
    }
    
    @Override
    public LogicalExpression copy() {
        return this;  // Immutable singleton, no need to copy
    }
    
    @Override
    public boolean isEquivalent(LogicalExpression other) {
        if (other instanceof ConstantExpr ce) {
            return this.value == ce.value;
        }
        return false;
    }
    
    @Override
    public String toString() {
        return value ? "TRUE" : "FALSE";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ConstantExpr that = (ConstantExpr) obj;
        return value == that.value;
    }
    
    @Override
    public int hashCode() {
        return Boolean.hashCode(value);
    }
}
