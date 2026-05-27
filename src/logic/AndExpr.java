package logic;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a conjunction (AND) of logical expressions.
 * sh:and (A, B, C) -> AndExpr(A, B, C)
 */
public class AndExpr extends LogicalExpression {
    private final List<LogicalExpression> args;
    
    public AndExpr(List<LogicalExpression> args) {
        this.args = new ArrayList<>(args);
    }
    
    public AndExpr(LogicalExpression... args) {
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
        return new AndExpr(copiedArgs);
    }
    
    @Override
    public boolean isEquivalent(LogicalExpression other) {
        if (!(other instanceof AndExpr otherAnd)) return false;
        if (args.size() != otherAnd.args.size()) return false;
        
        Set<LogicalExpression> thisSet = new HashSet<>(args);
        Set<LogicalExpression> otherSet = new HashSet<>(otherAnd.args);
        return thisSet.equals(otherSet);
    }
    
    @Override
    public String toString() {
        return "And(" + args.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ")) + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AndExpr andExpr = (AndExpr) o;
        return Objects.equals(new HashSet<>(args), new HashSet<>(andExpr.args));
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(new HashSet<>(args));
    }
}
