package reducer;

import logic.*;
import model.PropertyConstraint;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConstraintMerger interval-aware OR merge and conjunction unsat handling.
 *
 * The OR merge tests bypass LogicSimplifier by calling optimizeCountConstraintsInOr
 * directly, so the assertions reflect exactly what our interval algorithm produces.
 */
public class ConstraintMergerOrIntervalTest {

    private SymbolTable symbolTable;
    private ConstraintMerger merger;
    private LogicalExpression X;
    private LogicalExpression Y;

    @BeforeEach
    void setUp() {
        symbolTable = new SymbolTable();
        merger = new ConstraintMerger(symbolTable, new LogicSimplifier(symbolTable));
        X = symbolTable.createSymbolExpr("sh:class",
                ResourceFactory.createResource("http://example.org/X"));
        Y = symbolTable.createSymbolExpr("sh:class",
                ResourceFactory.createResource("http://example.org/Y"));
    }

    // ===== helpers =====

    private LogicalExpression min(int n) {
        return symbolTable.createSymbolExpr("sh:minCount",
                ResourceFactory.createTypedLiteral(String.valueOf(n), XSDDatatype.XSDinteger));
    }
    private LogicalExpression max(int n) {
        return symbolTable.createSymbolExpr("sh:maxCount",
                ResourceFactory.createTypedLiteral(String.valueOf(n), XSDDatatype.XSDinteger));
    }
    private LogicalExpression and(LogicalExpression... args) {
        return new AndExpr(args);
    }
    private LogicalExpression or(LogicalExpression... args) {
        return new OrExpr(Arrays.asList(args));
    }

    /** Decompose a result expression into per-disjunct (rest, [l, u]) for assertions. */
    private List<int[]> intervalsOf(LogicalExpression expr, LogicalExpression expectedRest) {
        List<LogicalExpression> disjuncts = (expr instanceof OrExpr o) ? o.getArgs() : List.of(expr);
        List<int[]> out = new ArrayList<>();
        for (LogicalExpression d : disjuncts) {
            List<LogicalExpression> conjs = (d instanceof AndExpr a) ? a.getArgs() : List.of(d);
            int lo = 0, hi = Integer.MAX_VALUE;
            boolean hasRest = false;
            for (LogicalExpression c : conjs) {
                if (!(c instanceof SymbolExpr sym)) { hasRest |= c.equals(expectedRest); continue; }
                SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
                if (info == null) { hasRest |= c.equals(expectedRest); continue; }
                String t = info.constraintType();
                if ("sh:minCount".equals(t)) lo = Math.max(lo, info.constraintValue().asLiteral().getInt());
                else if ("sh:maxCount".equals(t)) hi = Math.min(hi, info.constraintValue().asLiteral().getInt());
                else hasRest |= c.equals(expectedRest);
            }
            assertTrue(hasRest || expectedRest == null,
                    "expected rest " + expectedRest + " missing in disjunct " + d);
            out.add(new int[]{lo, hi});
        }
        return out;
    }

    // ===== Disjunction (interval-aware OR merge) =====

    /** Reported reproduction case: non-overlapping intervals must NOT merge. */
    @Test
    void nonOverlappingIntervals_areNotMerged() {
        // (X ∧ c≥2 ∧ c≤3) ∨ (X ∧ c≥5 ∧ c≤6)
        // Gap: 4 is allowed by neither side. Merging into [2,6] would wrongly admit c=4.
        LogicalExpression input = or(
                and(X, min(2), max(3)),
                and(X, min(5), max(6))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        assertSame(input, result, "non-touching intervals must be left untouched");
    }

    @Test
    void overlappingIntervals_mergeToUnion() {
        // (X ∧ c≥2 ∧ c≤4) ∨ (X ∧ c≥3 ∧ c≤6) → (X ∧ c≥2 ∧ c≤6)
        LogicalExpression input = or(
                and(X, min(2), max(4)),
                and(X, min(3), max(6))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        List<int[]> ivs = intervalsOf(result, X);
        assertEquals(1, ivs.size(), "overlapping intervals should collapse");
        assertArrayEquals(new int[]{2, 6}, ivs.get(0));
    }

    @Test
    void touchingIntegerIntervals_merge() {
        // [2,3] and [4,6] are integer-adjacent (gap == 1) → merge into [2,6].
        LogicalExpression input = or(
                and(X, min(2), max(3)),
                and(X, min(4), max(6))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        List<int[]> ivs = intervalsOf(result, X);
        assertEquals(1, ivs.size());
        assertArrayEquals(new int[]{2, 6}, ivs.get(0));
    }

    @Test
    void threeDisjuncts_transitiveUnion() {
        // [2,3] ∨ [5,6] ∨ [3,5] → all touch transitively → [2,6]
        LogicalExpression input = or(
                and(X, min(2), max(3)),
                and(X, min(5), max(6)),
                and(X, min(3), max(5))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        List<int[]> ivs = intervalsOf(result, X);
        assertEquals(1, ivs.size(), "transitive overlap should collapse all three");
        assertArrayEquals(new int[]{2, 6}, ivs.get(0));
    }

    @Test
    void differentRest_areNotMerged() {
        // Different "rest" parts (X vs Y) → separate buckets, no merge.
        LogicalExpression input = or(
                and(X, min(2), max(3)),
                and(Y, min(5), max(6))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        assertSame(input, result);
    }

    @Test
    void singleSidedMin_takesLooserBound() {
        // (X ∧ c≥2) ∨ (X ∧ c≥5) → [2,∞] ∪ [5,∞] = [2,∞] → (X ∧ c≥2)
        LogicalExpression input = or(
                and(X, min(2)),
                and(X, min(5))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        List<int[]> ivs = intervalsOf(result, X);
        assertEquals(1, ivs.size());
        assertArrayEquals(new int[]{2, Integer.MAX_VALUE}, ivs.get(0));
    }

    /** User-requested case: complementary half-bounds collapse to just the rest. */
    @Test
    void complementaryHalfBounds_collapseToRest() {
        // (X ∧ c≥2) ∨ (X ∧ c≤5) → [2,∞] ∪ [0,5] = [0,∞] → just X
        LogicalExpression input = or(
                and(X, min(2)),
                and(X, max(5))
        );
        LogicalExpression result = merger.optimizeCountConstraintsInOr(input);
        assertEquals(X, result, "[0,∞] union should drop both bounds, leaving just the rest");
    }

    // ===== Conjunction unsat handling =====

    @Test
    void conjunction_minGreaterThanMax_marksFalse() {
        // Two PCs on the same path: c≥5 ∧ c≤3  →  unsat
        Property path = ResourceFactory.createProperty("http://example.org/p");
        PropertyConstraint a = new PropertyConstraint(path);
        a.setMinCount(5);
        PropertyConstraint b = new PropertyConstraint(path);
        b.setMaxCount(3);

        List<PropertyConstraint> reduced = merger.reduceConstraints(Arrays.asList(a, b));

        assertEquals(1, reduced.size(), "PCs sharing a path should be merged into one");
        PropertyConstraint merged = reduced.get(0);
        LogicalExpression expr = merged.getLogicalExpression();
        assertTrue(expr instanceof ConstantExpr ce && !ce.getValue(),
                "unsat min>max must produce ConstantExpr.FALSE, got: " + expr);
        assertNull(merged.getMinCount(), "self-contradictory min should not be emitted");
        assertNull(merged.getMaxCount(), "self-contradictory max should not be emitted");
    }
}
