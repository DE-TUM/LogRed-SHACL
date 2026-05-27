package reducer;

import logic.*;
import model.*;
import optimizer.ConstraintOptimizer;
import parser.ShaclParser;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Simplifies individual branches of sh:xone constraints.
 *
 * Each xone branch is a blank node (RDF resource) representing a shape.
 * This class re-parses the branch as a PropertyConstraint, applies the
 * standard optimise → simplify pipeline, and rebuilds the branch as a
 * new blank node in the source model.
 *
 * The process is recursive: if a branch itself contains sh:xone, those
 * inner xone branches are simplified first (depth-first).
 *
 * Corresponds to Algorithm 11 (Step a) in the pseudocode.
 */
public class XoneBranchSimplifier {
    private static final Logger log = LoggerFactory.getLogger(XoneBranchSimplifier.class);

    private static final String SH = ShaclVocabulary.SH;

    private final Model sourceModel;
    private final SymbolTable symbolTable;
    private final ConstraintOptimizer optimizer;
    private final LogicSimplifier simplifier;
    private final ShaclParser parser;

    // Statistics
    private int branchesSimplified = 0;
    private int branchesUnchanged = 0;
    private int falseBranchesRemoved = 0;
    private int trueBranchCount = 0;

    // Flag set by simplifyOneBranch to signal FALSE result
    private boolean lastBranchWasFalse = false;
    // Flag set by simplifyOneBranch to signal TRUE (empty) result
    private boolean lastBranchWasTrue = false;

    public XoneBranchSimplifier(Model sourceModel, SymbolTable symbolTable,
                                 ConstraintOptimizer optimizer, LogicSimplifier simplifier) {
        this.sourceModel = sourceModel;
        this.symbolTable = symbolTable;
        this.optimizer = optimizer;
        this.simplifier = simplifier;
        this.parser = new ShaclParser();
    }

    public int getBranchesSimplified() { return branchesSimplified; }
    public int getBranchesUnchanged() { return branchesUnchanged; }
    public int getFalseBranchesRemoved() { return falseBranchesRemoved; }
    public int getTrueBranchCount() { return trueBranchCount; }

    /**
     * Parse a branch RDFNode as a PropertyConstraint.
     * Used by ShapeReducer for single-branch xone unwrap.
     */
    public PropertyConstraint parseBranch(RDFNode node) {
        if (!node.isResource()) return null;
        return parser.parseSinglePropertyShape(node.asResource(), sourceModel, symbolTable);
    }

    /**
     * Simplify a list of xone branch nodes in place.
     * Returns a new list where each branch has been parsed, optimised,
     * boolean-simplified, and rebuilt as a fresh blank node.
     *
     * @param alternatives the original xone branch RDFNodes
     * @return simplified branch RDFNodes (FALSE branches removed; content may differ)
     */
    @SuppressWarnings("unchecked")
    public List<RDFNode> simplifyBranches(List<RDFNode> alternatives) {
        trueBranchCount = 0;
        List<RDFNode> result = new ArrayList<>(alternatives.size());
        for (RDFNode branch : alternatives) {
            lastBranchWasFalse = false;
            lastBranchWasTrue = false;

            if (!branch.isResource()) {
                result.add(branch); // non-resource nodes pass through unchanged
                continue;
            }
            RDFNode simplified = simplifyOneBranch(branch.asResource());

            if (lastBranchWasFalse) {
                falseBranchesRemoved++;
                log.info("  Removed FALSE branch from xone");
                continue; // XONE(A, FALSE, B) → XONE(A, B)
            }

            if (lastBranchWasTrue) {
                trueBranchCount++;
            }

            result.add(simplified != null ? simplified : branch);
        }
        return result;
    }

    /**
     * Simplify a single xone branch.
     *
     * Pipeline: parse → optimise → simplify logic → recurse into sub-xone → rebuild
     *
     * @param branchRes the RDF resource representing the branch shape
     * @return a new blank node in the source model with simplified constraints,
     *         or null if the branch could not be simplified
     */
    private RDFNode simplifyOneBranch(Resource branchRes) {
        // URI resources are references to named NodeShapes — cannot simplify inline.
        // Treating them as TRUE/FALSE would be incorrect; keep them unchanged.
        if (!branchRes.isAnon()) {
            branchesUnchanged++;
            return null; // caller uses original branch unchanged (lastBranchWasFalse/True stay false)
        }

        // Step 1: Parse the branch as a PropertyConstraint
        PropertyConstraint pc = parser.parseSinglePropertyShape(branchRes, sourceModel, symbolTable);
        if (pc == null || pc.isEmpty()) {
            branchesUnchanged++;
            // Empty branch = no constraints = TRUE (matches everything)
            lastBranchWasTrue = true;
            return null;
        }

        // Step 2: Optimise (remove trivial constraints, detect conflicts)
        PropertyConstraint optimised = optimizer.optimize(pc);

        // Step 2b: Remove symbols from the expression that correspond to attrs
        // removed by the optimizer (e.g., minCount=0 was removed from attrs,
        // but its symbol still lives in the expression)
        if (optimised.getLogicalExpression() != null) {
            optimised.setLogicalExpression(
                    removeOrphanedDualTrackSymbols(optimised.getLogicalExpression(), pc, optimised));
        }

        // Step 3: Simplify the logical expression
        LogicalExpression expr = optimised.getLogicalExpression();
        if (expr != null) {
            LogicalExpression simplified = simplifier.simplify(expr);
            // TRUE means no constraint — clear the expression
            if (simplified instanceof ConstantExpr ce && ce.getValue()) {
                optimised.setLogicalExpression(null);
            } else {
                optimised.setLogicalExpression(simplified);
            }
        }

        // Check for FALSE (unsatisfiable) — don't rebuild, signal to caller
        if (optimised.getLogicalExpression() instanceof ConstantExpr ce && !ce.getValue()) {
            lastBranchWasFalse = true;
            branchesSimplified++;
            return null;
        }

        // Check for TRUE (all constraints simplified away)
        if (optimised.isEmpty()) {
            lastBranchWasTrue = true;
        }

        // Step 4: Recursively simplify any nested sh:xone in this branch
        Object innerXone = optimised.getConstraint(PropertyConstraint.ConstraintType.XONE);
        if (innerXone instanceof List<?> innerList && !innerList.isEmpty()) {
            List<RDFNode> innerAlternatives = (List<RDFNode>) innerList;
            List<RDFNode> simplifiedInner = simplifyBranches(innerAlternatives);
            optimised.setConstraint(PropertyConstraint.ConstraintType.XONE, simplifiedInner);
        }

        // Step 5: Check if anything actually changed
        // We rebuild regardless — structural dedup downstream will compare content
        RDFNode rebuilt = rebuildBranchNode(optimised);
        if (rebuilt != null) {
            branchesSimplified++;
        } else {
            branchesUnchanged++;
        }
        return rebuilt;
    }

    /**
     * Rebuild a PropertyConstraint as a fresh blank node in the source model.
     * This mirrors TurtleSerializer.serializePropertyConstraint but writes
     * directly into the source model so the rest of the pipeline (dedup,
     * final serialisation) works unchanged.
     */
    private RDFNode rebuildBranchNode(PropertyConstraint pc) {
        Resource node = sourceModel.createResource(); // fresh blank node

        // Write sh:path
        if (pc.getPath() != null) {
            node.addProperty(ShaclVocabulary.SH_PATH, pc.getPath());
        }

        // Write attribute constraints
        Map<PropertyConstraint.ConstraintType, Object> constraints = pc.getConstraints();
        for (Map.Entry<PropertyConstraint.ConstraintType, Object> entry : constraints.entrySet()) {
            if (entry.getKey() == PropertyConstraint.ConstraintType.OR) continue; // handled by logical expr
            writeConstraint(node, entry.getKey(), entry.getValue());
        }

        // Write logical expression
        LogicalExpression logicalExpr = removeDualTrackRedundancy(pc.getLogicalExpression(), constraints);
        if (logicalExpr != null) {
            writeLogicalExpression(node, logicalExpr);
        }

        return node;
    }

    // ==================== Constraint writing ====================

    private void writeConstraint(Resource target, PropertyConstraint.ConstraintType type, Object value) {
        Property prop = getProperty(type);
        if (prop == null) return;

        switch (type) {
            case MIN_COUNT, MAX_COUNT, MIN_LENGTH, MAX_LENGTH -> {
                if (value instanceof Number num) {
                    Literal lit = ResourceFactory.createTypedLiteral(
                            String.valueOf(num.intValue()), XSDDatatype.XSDinteger);
                    target.addLiteral(prop, lit);
                }
            }
            case MIN_EXCLUSIVE, MAX_EXCLUSIVE, MIN_INCLUSIVE, MAX_INCLUSIVE -> {
                if (value instanceof Number num) target.addLiteral(prop, num);
            }
            case DATATYPE, CLASS, NODE_KIND, NODE -> {
                if (value instanceof Resource res) target.addProperty(prop, res);
            }
            case PATTERN, FLAGS -> {
                if (value instanceof String str) target.addProperty(prop, str);
            }
            case HAS_VALUE -> {
                if (value instanceof RDFNode n) target.addProperty(prop, n);
            }
            case IN, LANGUAGE_IN -> {
                if (value instanceof List<?> list) {
                    RDFList rdfList = createRDFList(list);
                    target.addProperty(prop, rdfList);
                }
            }
            case XONE -> {
                if (value instanceof List<?> list) {
                    RDFList rdfList = createRDFListDeepCopy(list);
                    target.addProperty(prop, rdfList);
                }
            }
            case UNIQUE_LANG, QUALIFIED_VALUE_SHAPES_DISJOINT -> {
                if (value instanceof Boolean bool) target.addLiteral(prop, bool);
            }
            case QUALIFIED_VALUE_SHAPE -> {
                if (value instanceof Resource res) target.addProperty(prop, res);
            }
            case QUALIFIED_MIN_COUNT, QUALIFIED_MAX_COUNT -> {
                if (value instanceof Number num) {
                    Literal lit = ResourceFactory.createTypedLiteral(
                            String.valueOf(num.intValue()), XSDDatatype.XSDinteger);
                    target.addLiteral(prop, lit);
                }
            }
            default -> { /* skip OR, AND, NOT — handled via logical expression */ }
        }
    }

    private Property getProperty(PropertyConstraint.ConstraintType type) {
        return switch (type) {
            case MIN_COUNT -> ShaclVocabulary.SH_MIN_COUNT;
            case MAX_COUNT -> ShaclVocabulary.SH_MAX_COUNT;
            case MIN_LENGTH -> ShaclVocabulary.SH_MIN_LENGTH;
            case MAX_LENGTH -> ShaclVocabulary.SH_MAX_LENGTH;
            case MIN_EXCLUSIVE -> ShaclVocabulary.SH_MIN_EXCLUSIVE;
            case MAX_EXCLUSIVE -> ShaclVocabulary.SH_MAX_EXCLUSIVE;
            case MIN_INCLUSIVE -> ShaclVocabulary.SH_MIN_INCLUSIVE;
            case MAX_INCLUSIVE -> ShaclVocabulary.SH_MAX_INCLUSIVE;
            case DATATYPE -> ShaclVocabulary.SH_DATATYPE;
            case CLASS -> ShaclVocabulary.SH_CLASS;
            case NODE_KIND -> ShaclVocabulary.SH_NODE_KIND;
            case NODE -> ShaclVocabulary.SH_NODE;
            case PATTERN -> ShaclVocabulary.SH_PATTERN;
            case FLAGS -> ShaclVocabulary.SH_FLAGS;
            case HAS_VALUE -> ShaclVocabulary.SH_HAS_VALUE;
            case IN -> ShaclVocabulary.SH_IN;
            case LANGUAGE_IN -> ShaclVocabulary.SH_LANGUAGE_IN;
            case UNIQUE_LANG -> ShaclVocabulary.SH_UNIQUE_LANG;
            case EQUALS -> ShaclVocabulary.SH_EQUALS;
            case DISJOINT -> ShaclVocabulary.SH_DISJOINT;
            case LESS_THAN -> ShaclVocabulary.SH_LESS_THAN;
            case LESS_THAN_OR_EQUALS -> ShaclVocabulary.SH_LESS_THAN_OR_EQUALS;
            case QUALIFIED_VALUE_SHAPE -> ShaclVocabulary.SH_QUALIFIED_VALUE_SHAPE;
            case QUALIFIED_MIN_COUNT -> ShaclVocabulary.SH_QUALIFIED_MIN_COUNT;
            case QUALIFIED_MAX_COUNT -> ShaclVocabulary.SH_QUALIFIED_MAX_COUNT;
            case QUALIFIED_VALUE_SHAPES_DISJOINT -> ShaclVocabulary.SH_QUALIFIED_VALUE_SHAPES_DISJOINT;
            case XONE -> ShaclVocabulary.SH_XONE;
            default -> null;
        };
    }

    // ==================== Logical expression writing ====================

    private void writeLogicalExpression(Resource target, LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            writeSymbol(target, sym);
        } else if (expr instanceof OrExpr or) {
            writeOrExpression(target, or);
        } else if (expr instanceof AndExpr and) {
            // Top-level AND: emit each conjunct directly on the target
            for (LogicalExpression arg : and.getArgs()) {
                if (arg instanceof SymbolExpr sym) writeSymbol(target, sym);
                else if (arg instanceof OrExpr or2) writeOrExpression(target, or2);
                else if (arg instanceof NotExpr not) writeNotExpression(target, not);
                else if (arg instanceof AndExpr nested) writeLogicalExpression(target, nested);
            }
        } else if (expr instanceof NotExpr not) {
            writeNotExpression(target, not);
        }
    }

    private void writeSymbol(Resource target, SymbolExpr sym) {
        SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
        if (info == null) {
            log.warn("Symbol {} not found in symbol table during xone branch rebuild", sym.getName());
            return;
        }

        String constraintType = info.constraintType();
        RDFNode constraintValue = info.constraintValue();

        Property prop = constraintTypeToProperty(constraintType);
        if (prop == null) {
            log.warn("Unknown constraint type during xone rebuild: {}", constraintType);
            return;
        }

        // sh:xone inside branches requires special RDF list reconstruction
        if ("sh:xone".equals(constraintType) && constraintValue.isResource()) {
            writeXoneSymbol(target, prop, constraintValue.asResource());
            return;
        }

        target.addProperty(prop, constraintValue);
    }

    private void writeXoneSymbol(Resource target, Property prop, Resource listHead) {
        List<RDFNode> items = new ArrayList<>();
        Resource current = listHead;
        while (current != null && !current.equals(RDF.nil)) {
            Statement firstStmt = current.getProperty(RDF.first);
            if (firstStmt != null) items.add(deepCopyNode(firstStmt.getObject()));
            Statement restStmt = current.getProperty(RDF.rest);
            if (restStmt != null && restStmt.getObject().isResource()) {
                current = restStmt.getObject().asResource();
            } else break;
        }
        if (!items.isEmpty()) {
            RDFList rdfList = sourceModel.createList(items.iterator());
            target.addProperty(prop, rdfList);
        }
    }

    private void writeOrExpression(Resource target, OrExpr or) {
        Property shOr = ResourceFactory.createProperty(SH, "or");
        List<RDFNode> branchNodes = new ArrayList<>();
        for (LogicalExpression arg : or.getArgs()) {
            Resource branch = sourceModel.createResource();
            writeBranchExpression(branch, arg);
            branchNodes.add(branch);
        }
        RDFList rdfList = sourceModel.createList(branchNodes.iterator());
        target.addProperty(shOr, rdfList);
    }

    private void writeNotExpression(Resource target, NotExpr not) {
        Property shNot = ResourceFactory.createProperty(SH, "not");
        Resource inner = sourceModel.createResource();
        writeBranchExpression(inner, not.getArg());
        target.addProperty(shNot, inner);
    }

    private void writeBranchExpression(Resource branch, LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            writeSymbol(branch, sym);
        } else if (expr instanceof AndExpr and) {
            for (LogicalExpression arg : and.getArgs()) {
                writeBranchExpression(branch, arg);
            }
        } else if (expr instanceof OrExpr or) {
            writeOrExpression(branch, or);
        } else if (expr instanceof NotExpr not) {
            writeNotExpression(branch, not);
        }
    }

    private Property constraintTypeToProperty(String constraintType) {
        return switch (constraintType) {
            case "sh:nodeKind" -> ShaclVocabulary.SH_NODE_KIND;
            case "sh:class" -> ShaclVocabulary.SH_CLASS;
            case "sh:datatype" -> ShaclVocabulary.SH_DATATYPE;
            case "sh:node" -> ShaclVocabulary.SH_NODE;
            case "sh:hasValue" -> ShaclVocabulary.SH_HAS_VALUE;
            case "sh:in" -> ShaclVocabulary.SH_IN;
            case "sh:equals" -> ShaclVocabulary.SH_EQUALS;
            case "sh:disjoint" -> ShaclVocabulary.SH_DISJOINT;
            case "sh:lessThan" -> ShaclVocabulary.SH_LESS_THAN;
            case "sh:lessThanOrEquals" -> ShaclVocabulary.SH_LESS_THAN_OR_EQUALS;
            case "sh:xone" -> ShaclVocabulary.SH_XONE;
            case "sh:pattern" -> ShaclVocabulary.SH_PATTERN;
            case "sh:flags" -> ShaclVocabulary.SH_FLAGS;
            case "sh:minCount" -> ShaclVocabulary.SH_MIN_COUNT;
            case "sh:maxCount" -> ShaclVocabulary.SH_MAX_COUNT;
            case "sh:minLength" -> ShaclVocabulary.SH_MIN_LENGTH;
            case "sh:maxLength" -> ShaclVocabulary.SH_MAX_LENGTH;
            case "sh:minInclusive" -> ShaclVocabulary.SH_MIN_INCLUSIVE;
            case "sh:maxInclusive" -> ShaclVocabulary.SH_MAX_INCLUSIVE;
            case "sh:minExclusive" -> ShaclVocabulary.SH_MIN_EXCLUSIVE;
            case "sh:maxExclusive" -> ShaclVocabulary.SH_MAX_EXCLUSIVE;
            case "sh:uniqueLang" -> ShaclVocabulary.SH_UNIQUE_LANG;
            default -> null;
        };
    }

    // ==================== Dual-track dedup ====================

    /**
     * Remove top-level AND conjuncts from φ that duplicate plain attributes.
     * Same logic as TurtleSerializer.removeDualTrackRedundancy.
     */
    private LogicalExpression removeDualTrackRedundancy(
            LogicalExpression expr, Map<PropertyConstraint.ConstraintType, Object> attrs) {
        if (expr == null || attrs.isEmpty()) return expr;

        java.util.function.Predicate<SymbolExpr> isRedundant = sym -> {
            SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
            if (info == null) return false;
            String type = info.constraintType();
            PropertyConstraint.ConstraintType ct = symbolTypeToConstraintType(type);
            if (ct == null) return false;
            Object attrValue = attrs.get(ct);
            return attrValue != null && valuesMatch(info.constraintValue(), attrValue);
        };

        if (expr instanceof SymbolExpr sym && isRedundant.test(sym)) return null;

        if (expr instanceof AndExpr and) {
            List<LogicalExpression> kept = new ArrayList<>();
            for (LogicalExpression arg : and.getArgs()) {
                if (arg instanceof SymbolExpr sym && isRedundant.test(sym)) continue;
                kept.add(arg);
            }
            if (kept.size() == and.getArgs().size()) return expr;
            if (kept.isEmpty()) return null;
            if (kept.size() == 1) return kept.get(0);
            return LogicalExpression.and(kept);
        }

        return expr;
    }

    private static PropertyConstraint.ConstraintType symbolTypeToConstraintType(String type) {
        return switch (type) {
            case "sh:nodeKind" -> PropertyConstraint.ConstraintType.NODE_KIND;
            case "sh:datatype" -> PropertyConstraint.ConstraintType.DATATYPE;
            case "sh:class" -> PropertyConstraint.ConstraintType.CLASS;
            case "sh:node" -> PropertyConstraint.ConstraintType.NODE;
            case "sh:hasValue" -> PropertyConstraint.ConstraintType.HAS_VALUE;
            case "sh:minCount" -> PropertyConstraint.ConstraintType.MIN_COUNT;
            case "sh:maxCount" -> PropertyConstraint.ConstraintType.MAX_COUNT;
            case "sh:minLength" -> PropertyConstraint.ConstraintType.MIN_LENGTH;
            case "sh:maxLength" -> PropertyConstraint.ConstraintType.MAX_LENGTH;
            case "sh:pattern" -> PropertyConstraint.ConstraintType.PATTERN;
            case "sh:flags" -> PropertyConstraint.ConstraintType.FLAGS;
            case "sh:minInclusive" -> PropertyConstraint.ConstraintType.MIN_INCLUSIVE;
            case "sh:maxInclusive" -> PropertyConstraint.ConstraintType.MAX_INCLUSIVE;
            case "sh:minExclusive" -> PropertyConstraint.ConstraintType.MIN_EXCLUSIVE;
            case "sh:maxExclusive" -> PropertyConstraint.ConstraintType.MAX_EXCLUSIVE;
            case "sh:uniqueLang" -> PropertyConstraint.ConstraintType.UNIQUE_LANG;
            case "sh:equals" -> PropertyConstraint.ConstraintType.EQUALS;
            case "sh:disjoint" -> PropertyConstraint.ConstraintType.DISJOINT;
            case "sh:lessThan" -> PropertyConstraint.ConstraintType.LESS_THAN;
            case "sh:lessThanOrEquals" -> PropertyConstraint.ConstraintType.LESS_THAN_OR_EQUALS;
            default -> null;
        };
    }

    private static boolean valuesMatch(RDFNode symValue, Object attrValue) {
        if (symValue == null || attrValue == null) return false;
        if (attrValue instanceof RDFNode attrNode) return symValue.equals(attrNode);
        if (attrValue instanceof Resource r) return symValue.isResource() && symValue.asResource().getURI().equals(r.getURI());
        if (attrValue instanceof Integer intVal && symValue.isLiteral()) {
            try { return symValue.asLiteral().getInt() == intVal; } catch (Exception e) { return false; }
        }
        if (attrValue instanceof Number numVal && symValue.isLiteral()) {
            try { return Double.compare(symValue.asLiteral().getDouble(), numVal.doubleValue()) == 0; } catch (Exception e) { return false; }
        }
        if (attrValue instanceof String strVal && symValue.isLiteral()) return strVal.equals(symValue.asLiteral().getString());
        if (attrValue instanceof Boolean boolVal && symValue.isLiteral()) {
            try { return boolVal == symValue.asLiteral().getBoolean(); } catch (Exception e) { return false; }
        }
        return false;
    }

    // ==================== Orphaned symbol cleanup ====================

    /**
     * Remove symbols from the expression that referenced dual-track attrs
     * which the optimizer deleted (e.g., minCount=0).  We detect them by
     * comparing which ConstraintType keys were present before optimisation
     * but absent afterwards.
     */
    private LogicalExpression removeOrphanedDualTrackSymbols(
            LogicalExpression expr,
            PropertyConstraint before, PropertyConstraint after) {
        if (expr == null) return null;

        // Collect constraint types that the optimizer removed
        Set<PropertyConstraint.ConstraintType> removedTypes =
                java.util.EnumSet.noneOf(PropertyConstraint.ConstraintType.class);
        for (PropertyConstraint.ConstraintType ct : before.getConstraints().keySet()) {
            if (!after.getConstraints().containsKey(ct)) {
                removedTypes.add(ct);
            }
        }
        if (removedTypes.isEmpty()) return expr;

        // Build a predicate that identifies symbols for removed attrs
        java.util.function.Predicate<SymbolExpr> isOrphaned = sym -> {
            SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
            if (info == null) return false;
            PropertyConstraint.ConstraintType ct = symbolTypeToConstraintType(info.constraintType());
            return ct != null && removedTypes.contains(ct);
        };

        return stripSymbols(expr, isOrphaned);
    }

    /**
     * Remove matching symbols from an expression tree.
     * In AND, removed symbols are dropped; in OR, they become TRUE; elsewhere unchanged.
     */
    private LogicalExpression stripSymbols(
            LogicalExpression expr, java.util.function.Predicate<SymbolExpr> shouldRemove) {
        if (expr instanceof SymbolExpr sym) {
            return shouldRemove.test(sym) ? null : expr;
        }
        if (expr instanceof AndExpr and) {
            List<LogicalExpression> kept = new ArrayList<>();
            for (LogicalExpression arg : and.getArgs()) {
                LogicalExpression cleaned = stripSymbols(arg, shouldRemove);
                if (cleaned != null) kept.add(cleaned);
            }
            if (kept.size() == and.getArgs().size()) return expr; // nothing removed
            if (kept.isEmpty()) return null;
            if (kept.size() == 1) return kept.get(0);
            return LogicalExpression.and(kept);
        }
        if (expr instanceof OrExpr or) {
            List<LogicalExpression> kept = new ArrayList<>();
            for (LogicalExpression arg : or.getArgs()) {
                LogicalExpression cleaned = stripSymbols(arg, shouldRemove);
                if (cleaned != null) kept.add(cleaned);
                // a removed symbol in OR effectively becomes TRUE → whole OR is TRUE
                else return ConstantExpr.TRUE;
            }
            if (kept.size() == or.getArgs().size()) return expr;
            if (kept.isEmpty()) return ConstantExpr.TRUE;
            if (kept.size() == 1) return kept.get(0);
            return LogicalExpression.or(kept);
        }
        if (expr instanceof NotExpr not) {
            LogicalExpression cleaned = stripSymbols(not.getArg(), shouldRemove);
            if (cleaned == null) return null; // NOT(removed) → remove
            if (cleaned == not.getArg()) return expr;
            return LogicalExpression.not(cleaned);
        }
        return expr;
    }

    // ==================== RDF utility methods ====================

    private RDFNode deepCopyNode(RDFNode node) {
        if (node.isURIResource()) return sourceModel.createResource(node.asResource().getURI());
        if (node.isLiteral()) return node;
        // Blank node: create new blank node and copy all properties
        Resource src = node.asResource();
        Resource dst = sourceModel.createResource();
        StmtIterator stmts = sourceModel.listStatements(src, null, (RDFNode) null);
        while (stmts.hasNext()) {
            Statement stmt = stmts.next();
            dst.addProperty(stmt.getPredicate(), deepCopyNode(stmt.getObject()));
        }
        return dst;
    }

    private RDFList createRDFList(List<?> items) {
        List<RDFNode> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof RDFNode node) nodes.add(node);
        }
        return sourceModel.createList(nodes.iterator());
    }

    private RDFList createRDFListDeepCopy(List<?> items) {
        List<RDFNode> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof RDFNode node) nodes.add(deepCopyNode(node));
        }
        return sourceModel.createList(nodes.iterator());
    }
}
