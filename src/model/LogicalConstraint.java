package model;

import java.util.*;

/**
 * Represents a logical constraint (sh:and, sh:or, sh:not, sh:xone).
 * Uses a tree structure to represent nested logical expressions.
 */
public class LogicalConstraint {
    
    public enum LogicalType {
        AND,    // sh:and - conjunction
        OR,     // sh:or - disjunction  
        NOT,    // sh:not - negation
        XONE    // sh:xone - exactly one
    }
    
    private final LogicalType type;
    private final List<Object> operands; // Can be PropertyConstraint, LogicalConstraint, or NodeShape reference
    
    public LogicalConstraint(LogicalType type) {
        this.type = type;
        this.operands = new ArrayList<>();
    }
    
    public LogicalType getType() {
        return type;
    }
    
    public List<Object> getOperands() {
        return operands;
    }
    
    public void addOperand(Object operand) {
        operands.add(operand);
    }
    
    public void addOperands(Collection<?> operands) {
        this.operands.addAll(operands);
    }
    
    /**
     * Replace all operands with the given list.
     * Used by xone deduplication to update alternatives in place.
     */
    public void replaceOperands(List<Object> newOperands) {
        this.operands.clear();
        this.operands.addAll(newOperands);
    }
    
    public int getOperandCount() {
        return operands.size();
    }
    
    public boolean isUnary() {
        return type == LogicalType.NOT;
    }
    
    /**
     * Deep copy this logical constraint
     */
    public LogicalConstraint copy() {
        LogicalConstraint copy = new LogicalConstraint(type);
        for (Object operand : operands) {
            if (operand instanceof PropertyConstraint pc) {
                copy.addOperand(pc.copy());
            } else if (operand instanceof LogicalConstraint lc) {
                copy.addOperand(lc.copy());
            } else {
                copy.addOperand(operand); // Reference types (NodeShape URI)
            }
        }
        return copy;
    }
    
    @Override
    public String toString() {
        return "LogicalConstraint{" + type + ", operands=" + operands.size() + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogicalConstraint that = (LogicalConstraint) o;
        return type == that.type && Objects.equals(operands, that.operands);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, operands);
    }
}
