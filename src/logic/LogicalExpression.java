package logic;

import java.util.*;

/**
 * Abstract base class for logical expressions.
 * Represents a tree structure similar to SymPy's boolean algebra.
 */
public abstract class LogicalExpression {
    
    /**
     * Get all free symbols in this expression.
     */
    public abstract Set<String> getFreeSymbols();
    
    /**
     * Deep copy this expression.
     */
    public abstract LogicalExpression copy();
    
    /**
     * Check if this expression is equivalent to another.
     */
    public abstract boolean isEquivalent(LogicalExpression other);
    
    /**
     * Get the arguments (children) of this expression.
     * For Symbol, returns empty list.
     */
    public List<LogicalExpression> getArgs() {
        return Collections.emptyList();
    }
    
    /**
     * Check if this is an Or expression.
     */
    public boolean isOr() {
        return this instanceof OrExpr;
    }
    
    /**
     * Check if this is an And expression.
     */
    public boolean isAnd() {
        return this instanceof AndExpr;
    }
    
    /**
     * Check if this is a Symbol.
     */
    public boolean isSymbol() {
        return this instanceof SymbolExpr;
    }
    
    /**
     * Check if this is a Not expression.
     */
    public boolean isNot() {
        return this instanceof NotExpr;
    }
    
    /**
     * Static factory method for creating Or expressions.
     */
    public static LogicalExpression or(LogicalExpression... args) {
        return or(Arrays.asList(args));
    }
    
    public static LogicalExpression or(List<LogicalExpression> args) {
        if (args.isEmpty()) return null;
        if (args.size() == 1) return args.get(0);
        return new OrExpr(args);
    }
    
    /**
     * Static factory method for creating And expressions.
     */
    public static LogicalExpression and(LogicalExpression... args) {
        return and(Arrays.asList(args));
    }
    
    public static LogicalExpression and(List<LogicalExpression> args) {
        if (args.isEmpty()) return null;
        if (args.size() == 1) return args.get(0);
        return new AndExpr(args);
    }
    
    /**
     * Static factory method for creating Not expressions.
     */
    public static LogicalExpression not(LogicalExpression arg) {
        return new NotExpr(arg);
    }
    
    /**
     * Static factory method for creating Symbol expressions.
     */
    public static LogicalExpression symbol(String name) {
        return new SymbolExpr(name);
    }
}
