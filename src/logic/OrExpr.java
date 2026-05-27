package logic;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a disjunction (OR) of logical expressions.
 * sh:or (A, B, C) -> OrExpr(A, B, C)
 */
public class OrExpr extends LogicalExpression {
    private final List<LogicalExpression> args;
    
    public OrExpr(List<LogicalExpression> args) {
        this.args = new ArrayList<>(args);
    }
    
    public OrExpr(LogicalExpression... args) {
        this.args = new ArrayList<>(Arrays.asList(args));
    }
    
    @Override
    public List<LogicalExpression> getArgs() {
        return Collections.unmodifiableList(args);
    }
    
    @Override
    public Set<String> getFreeSymbols() {
        Set<String> symbols = new HashSet<>();
        for (LogicalExpression arg : args) {
            symbols.addAll(arg.getFreeSymbols());
        }
        return symbols;
    }
    
    @Override
    public LogicalExpression copy() {
        List<LogicalExpression> copiedArgs = args.stream()
                .map(LogicalExpression::copy)
                .collect(Collectors.toList());
        return new OrExpr(copiedArgs);
    }
    
    @Override
    public boolean isEquivalent(LogicalExpression other) {
        if (!(other instanceof OrExpr otherOr)) return false;
        if (args.size() != otherOr.args.size()) return false;
        
        // Check if all args are equivalent (order-independent)
        Set<LogicalExpression> thisSet = new HashSet<>(args);
        Set<LogicalExpression> otherSet = new HashSet<>(otherOr.args);
        return thisSet.equals(otherSet);
    }
    
    @Override
    public String toString() {
        return "Or(" + args.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ")) + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrExpr orExpr = (OrExpr) o;
        return Objects.equals(new HashSet<>(args), new HashSet<>(orExpr.args));
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(new HashSet<>(args));
    }
}
