package logic;

import java.util.*;

/**
 * Represents a negation (NOT) of a logical expression.
 * sh:not (A) -> NotExpr(A)
 */
public class NotExpr extends LogicalExpression {
    private final LogicalExpression arg;
    
    public NotExpr(LogicalExpression arg) {
        this.arg = arg;
    }
    
    public LogicalExpression getArg() {
        return arg;
    }
    
    @Override
    public List<LogicalExpression> getArgs() {
        return Collections.singletonList(arg);
    }
    
    @Override
    public Set<String> getFreeSymbols() {
        return arg.getFreeSymbols();
    }
    
    @Override
    public LogicalExpression copy() {
        return new NotExpr(arg.copy());
    }
    
    @Override
    public boolean isEquivalent(LogicalExpression other) {
        if (!(other instanceof NotExpr otherNot)) return false;
        return arg.isEquivalent(otherNot.arg);
    }
    
    @Override
    public String toString() {
        return "Not(" + arg + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotExpr notExpr = (NotExpr) o;
        return Objects.equals(arg, notExpr.arg);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(arg);
    }
}
