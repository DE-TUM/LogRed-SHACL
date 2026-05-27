package parser;

import model.*;
import logic.*;
import checker.ConstraintValidator;
import checker.ConstraintValidator.ValidationIssue;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * SHACL Parser that converts SHACL constraints to logical expressions.
 * 
 * This parser translates SHACL constraints into symbolic logical expressions
 * that can be simplified using boolean algebra. Each constraint (nodeKind, class,
 * datatype, etc.) is assigned a unique symbol, and sh:or/sh:and structures are
 * converted to Or/And expressions.
 */
public class ShaclParser {
    private static final Logger log = LoggerFactory.getLogger(ShaclParser.class);
    
    // Import shared SHACL vocabulary constants
    private static final Property shTargetClass = ShaclVocabulary.SH_TARGET_CLASS;
    private static final Property shTargetNode = ShaclVocabulary.SH_TARGET_NODE;
    private static final Property shTargetSubjectsOf = ShaclVocabulary.SH_TARGET_SUBJECTS_OF;
    private static final Property shTargetObjectsOf = ShaclVocabulary.SH_TARGET_OBJECTS_OF;
    private static final Property shProperty = ShaclVocabulary.SH_PROPERTY;
    private static final Property shPath = ShaclVocabulary.SH_PATH;
    private static final Property shMinCount = ShaclVocabulary.SH_MIN_COUNT;
    private static final Property shMaxCount = ShaclVocabulary.SH_MAX_COUNT;
    private static final Property shDatatype = ShaclVocabulary.SH_DATATYPE;
    private static final Property shClass = ShaclVocabulary.SH_CLASS;
    private static final Property shNodeKind = ShaclVocabulary.SH_NODE_KIND;
    private static final Property shNodeKindWrong = ShaclVocabulary.SH_NODE_KIND_WRONG;
    private static final Property shMinLength = ShaclVocabulary.SH_MIN_LENGTH;
    private static final Property shMaxLength = ShaclVocabulary.SH_MAX_LENGTH;
    private static final Property shPattern = ShaclVocabulary.SH_PATTERN;
    private static final Property shFlags = ShaclVocabulary.SH_FLAGS;
    private static final Property shIn = ShaclVocabulary.SH_IN;
    private static final Property shHasValue = ShaclVocabulary.SH_HAS_VALUE;
    private static final Property shMinInclusive = ShaclVocabulary.SH_MIN_INCLUSIVE;
    private static final Property shMaxInclusive = ShaclVocabulary.SH_MAX_INCLUSIVE;
    private static final Property shMinExclusive = ShaclVocabulary.SH_MIN_EXCLUSIVE;
    private static final Property shMaxExclusive = ShaclVocabulary.SH_MAX_EXCLUSIVE;
    private static final Property shLanguageIn = ShaclVocabulary.SH_LANGUAGE_IN;
    private static final Property shUniqueLang = ShaclVocabulary.SH_UNIQUE_LANG;
    private static final Property shNot = ShaclVocabulary.SH_NOT;
    private static final Property shAnd = ShaclVocabulary.SH_AND;
    private static final Property shOr = ShaclVocabulary.SH_OR;
    private static final Property shXone = ShaclVocabulary.SH_XONE;
    private static final Property shNode = ShaclVocabulary.SH_NODE;
    private static final Property shEquals = ShaclVocabulary.SH_EQUALS;
    private static final Property shDisjoint = ShaclVocabulary.SH_DISJOINT;
    private static final Property shLessThan = ShaclVocabulary.SH_LESS_THAN;
    private static final Property shLessThanOrEquals = ShaclVocabulary.SH_LESS_THAN_OR_EQUALS;
    private static final Property shQualifiedValueShape = ShaclVocabulary.SH_QUALIFIED_VALUE_SHAPE;
    private static final Property shQualifiedMinCount = ShaclVocabulary.SH_QUALIFIED_MIN_COUNT;
    private static final Property shQualifiedMaxCount = ShaclVocabulary.SH_QUALIFIED_MAX_COUNT;
    private static final Property shQualifiedValueShapesDisjoint = ShaclVocabulary.SH_QUALIFIED_VALUE_SHAPES_DISJOINT;
    private static final Property shClosed = ShaclVocabulary.SH_CLOSED;
    private static final Property shIgnoredProperties = ShaclVocabulary.SH_IGNORED_PROPERTIES;
    private static final Resource shNodeShape = ShaclVocabulary.SH_NODE_SHAPE;
    
    private Model model;
    private SymbolTable symbolTable;
    private ConstraintValidator validator;
    
    // Configuration options
    private boolean validationEnabled = true;    // Validate constraints for consistency
    
    /**
     * Value object returned by buildLogicalExpression to avoid side-channel instance fields.
     * Carries the logical expression plus any count constraints merged from OR branches
     * and any sh:xone alternatives parsed from the PropertyShape.
     */
    record ParseResult(LogicalExpression expression, Integer mergedMinCount, 
                       Integer mergedMaxCount, List<RDFNode> xoneAlternatives) {
        static ParseResult empty() { return new ParseResult(null, null, null, null); }
        static ParseResult ofExpr(LogicalExpression expr) { return new ParseResult(expr, null, null, null); }
    }
    
    /**
     * Enable or disable constraint validation during parsing.
     * When enabled, detects conflicts like minCount > maxCount, incompatible nodeKind/datatype, etc.
     * @param enabled true to enable validation (default), false to disable
     * @return this parser for fluent configuration
     */
    public ShaclParser setValidationEnabled(boolean enabled) {
        this.validationEnabled = enabled;
        return this;
    }
    
    public ShaclParser() {
        // All SHACL properties are static constants from ShaclVocabulary
    }
    
    public ShapeGraph parse(Path filePath) throws IOException {
        log.info("Loading SHACL shapes from: {}", filePath);
        long startTime = System.currentTimeMillis();
        model = RDFDataMgr.loadModel(filePath.toString());
        long loadTime = System.currentTimeMillis() - startTime;
        log.info("Loaded {} triples in {}ms", model.size(), loadTime);
        
        return parseModelInternal();
    }
    
    public ShapeGraph parse(Model inputModel) {
        this.model = inputModel;
        return parseModelInternal();
    }
    
    public ShapeGraph parse(InputStream inputStream, String format) {
        model = ModelFactory.createDefaultModel();
        Lang lang = getLang(format);
        RDFDataMgr.read(model, inputStream, lang);
        return parseModelInternal();
    }
    
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }
    
    /**
     * Parse a single RDF resource as a PropertyShape using an existing symbol table.
     * This is used for xone intra-branch simplification: each xone branch (a blank
     * node in the source model) is re-parsed so its constraints can be optimised
     * and boolean-simplified.
     *
     * @param propRes the RDF resource representing a PropertyShape (typically a blank node)
     * @param sourceModel the RDF model containing the resource's triples
     * @param existingSymbolTable the symbol table to reuse for symbol assignment
     * @return the parsed PropertyConstraint, or null if parsing fails
     */
    public PropertyConstraint parseSinglePropertyShape(Resource propRes, Model sourceModel, SymbolTable existingSymbolTable) {
        this.model = sourceModel;
        this.symbolTable = existingSymbolTable;
        this.validator = null; // skip validation for branch re-parsing
        try {
            return parsePropertyConstraint(propRes);
        } catch (Exception e) {
            log.warn("Failed to parse xone branch {}: {}", propRes, e.getMessage());
            return null;
        }
    }
    
    private Lang getLang(String format) {
        if (format == null) return Lang.TURTLE;
        String upper = format.toUpperCase();
        return switch (upper) {
            case "TURTLE", "TTL" -> Lang.TURTLE;
            case "RDF/XML", "RDFXML", "XML" -> Lang.RDFXML;
            case "N3" -> Lang.N3;
            case "NT", "NTRIPLES" -> Lang.NTRIPLES;
            case "JSONLD" -> Lang.JSONLD;
            default -> Lang.TURTLE;
        };
    }
    
    /**
     * Internal method: initialize shared state and parse the model.
     */
    private ShapeGraph parseModelInternal() {
        symbolTable = new SymbolTable();
        validator = validationEnabled ? new ConstraintValidator() : null;
        
        log.info("Parsing SHACL model with logical expression support...");
        long startTime = System.currentTimeMillis();
        ShapeGraph shapeGraph = new ShapeGraph();
        shapeGraph.setSymbolTable(symbolTable);
        shapeGraph.setSourceModel(model);
        
        List<Resource> nodeShapes = findNodeShapes();
        log.info("Found {} node shapes", nodeShapes.size());
        
        // Sequential parsing (Jena Model is NOT thread-safe)
        int count = 0;
        int logInterval = Math.max(1, nodeShapes.size() / 10);
        
        for (Resource shapeRes : nodeShapes) {
            count++;
            if (count % logInterval == 0 || count == 1 || count == nodeShapes.size()) {
                log.info("Processing shape {}/{}: {}", count, nodeShapes.size(), shapeRes.getURI());
            }
            try {
                NodeShape shape = parseNodeShapeWithoutMerge(shapeRes);
                shapeGraph.addNodeShape(shape);
            } catch (Exception e) {
                log.warn("Failed to parse shape {}: {}", shapeRes.getURI(), e.getMessage(), e);
            }
        }
        
        long parseTime = System.currentTimeMillis() - startTime;
        log.info("Parsing completed in {}ms. Symbol table has {} symbols.", 
                parseTime, symbolTable.getAllSymbols().size());
        
        return shapeGraph;
    }
    

    

    
    /**
     * Parse NodeShape without constraint merging (for parallel processing).
     */
    private NodeShape parseNodeShapeWithoutMerge(Resource shapeRes) {
        String uri = shapeRes.getURI();
        NodeShape shape = new NodeShape(uri);
        
        parseTargets(shapeRes, shape);
        
        // Parse property constraints without merging
        StmtIterator propStmts = model.listStatements(shapeRes, shProperty, (RDFNode) null);
        while (propStmts.hasNext()) {
            RDFNode propNode = propStmts.next().getObject();
            if (propNode.isResource()) {
                PropertyConstraint pc = parsePropertyConstraint(propNode.asResource());
                if (pc != null && !pc.isEmpty()) {
                    shape.addPropertyConstraint(pc);
                }
            }
        }
        
        // Parse node-level logical constraints
        parseNodeLogicalConstraints(shapeRes, shape);
        
        // Parse sh:closed and sh:ignoredProperties
        parseClosedConstraint(shapeRes, shape);

        // Parse sh:hasClass (non-standard magic seed from MagicShapes.jar)
        Statement hasClassStmt = shapeRes.getProperty(ShaclVocabulary.SH_HAS_CLASS);
        if (hasClassStmt != null && hasClassStmt.getObject().isResource()) {
            shape.setHasClass(hasClassStmt.getObject().asResource());
        }

        // Parse sh:node at NodeShape level (pass-through, not simplified)
        StmtIterator nodeStmts = shapeRes.listProperties(ShaclVocabulary.SH_NODE);
        while (nodeStmts.hasNext()) {
            RDFNode obj = nodeStmts.next().getObject();
            if (obj.isResource()) shape.addNodeRef(obj.asResource());
        }

        return shape;
    }
    
    /**
     * Parse sh:closed and sh:ignoredProperties from a NodeShape.
     * sh:closed is a boolean indicating if the shape is closed.
     * sh:ignoredProperties is an RDF list of properties to ignore when sh:closed is true.
     */
    private void parseClosedConstraint(Resource shapeRes, NodeShape shape) {
        Statement closedStmt = shapeRes.getProperty(shClosed);
        if (closedStmt != null && closedStmt.getObject().isLiteral()) {
            boolean closedValue = closedStmt.getBoolean();
            shape.setClosed(closedValue);
            log.debug("Shape {} has sh:closed = {}", shape.getUri(), closedValue);
        }
        
        Statement ignoredStmt = shapeRes.getProperty(shIgnoredProperties);
        if (ignoredStmt != null && ignoredStmt.getObject().isResource()) {
            List<RDFNode> ignoredList = parseRDFList(ignoredStmt.getObject().asResource());
            for (RDFNode node : ignoredList) {
                if (node.isResource()) {
                    shape.addIgnoredProperty(node.asResource());
                }
            }
            log.debug("Shape {} has sh:ignoredProperties with {} entries", 
                     shape.getUri(), shape.getIgnoredProperties().size());
        }
    }
    
    private List<Resource> findNodeShapes() {
        Set<Resource> shapes = new HashSet<>();
        StmtIterator typeStmts = model.listStatements(null, RDF.type, shNodeShape);
        while (typeStmts.hasNext()) {
            shapes.add(typeStmts.next().getSubject());
        }
        addShapesWithProperty(shapes, shTargetClass);
        addShapesWithProperty(shapes, shTargetNode);
        addShapesWithProperty(shapes, shTargetSubjectsOf);
        addShapesWithProperty(shapes, shTargetObjectsOf);
        return new ArrayList<>(shapes);
    }
    
    private void addShapesWithProperty(Set<Resource> shapes, Property prop) {
        StmtIterator stmts = model.listStatements(null, prop, (RDFNode) null);
        while (stmts.hasNext()) {
            Resource subject = stmts.next().getSubject();
            if (subject.isURIResource()) {
                shapes.add(subject);
            }
        }
    }
    
    private void parseTargets(Resource shapeRes, NodeShape shape) {
        parseTargetProperty(shapeRes, shTargetClass, shape::addTargetClass);
        parseTargetProperty(shapeRes, shTargetNode, shape::addTargetNode);
        parseTargetProperty(shapeRes, shTargetSubjectsOf, shape::addTargetSubjectsOf);
        parseTargetProperty(shapeRes, shTargetObjectsOf, shape::addTargetObjectsOf);
    }
    
    private void parseTargetProperty(Resource shapeRes, Property prop, java.util.function.Consumer<Resource> adder) {
        StmtIterator stmts = model.listStatements(shapeRes, prop, (RDFNode) null);
        while (stmts.hasNext()) {
            RDFNode node = stmts.next().getObject();
            if (node.isResource()) adder.accept(node.asResource());
        }
    }
    
    /**
     * Parse a property constraint and convert all constraints to logical expressions.
     */
    private PropertyConstraint parsePropertyConstraint(Resource propRes) {
        PropertyConstraint pc = new PropertyConstraint();
        
        if (propRes.isURIResource()) {
            pc.setUri(propRes.getURI());
        }
        
        // Parse path
        Statement pathStmt = propRes.getProperty(shPath);
        if (pathStmt != null && pathStmt.getObject().isResource()) {
            pc.setPath(pathStmt.getObject().asResource());
        }
        
        // Parse property-level attributes (not converted to logical constraints)
        parsePropertyAttributes(propRes, pc);
        
        // Build logical expression from constraints (returns all results in a value object)
        ParseResult result = buildLogicalExpression(propRes);
        
        // If sh:xone was found, store it as an opaque attribute
        if (result.xoneAlternatives() != null && !result.xoneAlternatives().isEmpty()) {
            pc.setConstraint(PropertyConstraint.ConstraintType.XONE, result.xoneAlternatives());
            log.debug("Stored sh:xone with {} alternatives on PropertyShape", result.xoneAlternatives().size());
        }
        
        // If OR branches were merged and we have merged counts, apply them to the PropertyConstraint
        if (result.mergedMinCount() != null || result.mergedMaxCount() != null) {
            if (pc.getMinCount() == null && result.mergedMinCount() != null) {
                pc.setMinCount(result.mergedMinCount());
                log.debug("Applied merged OR minCount: {}", result.mergedMinCount());
            }
            if (pc.getMaxCount() == null && result.mergedMaxCount() != null) {
                pc.setMaxCount(result.mergedMaxCount());
                log.debug("Applied merged OR maxCount: {}", result.mergedMaxCount());
            }
        }
        
        // Don't simplify here - simplification happens in ConstraintMerger after merging
        if (result.expression() != null) {
            pc.setLogicalExpression(result.expression());
            log.debug("Property {}: {}", pc.getPath(), result.expression());
        }
        
        // Validate constraint consistency (log warnings during parsing)
        if (validator != null) {
            List<ValidationIssue> issues = validator.validate(pc);
            if (!issues.isEmpty()) {
                validator.logIssues(issues);
            }
        }
        
        return pc;
    }
    
    /**
     * Parse property-level attributes that are NOT part of logical expressions.
     * These include minCount, maxCount, minLength, maxLength, etc.
     */
    private void parsePropertyAttributes(Resource propRes, PropertyConstraint pc) {
        // Type constraints stored as attributes (not as logical symbols)
        // This matches Python behavior where sh:nodeKind, sh:datatype are property-level attributes
        Statement nodeKindStmt = propRes.getProperty(shNodeKind);
        if (nodeKindStmt != null && nodeKindStmt.getObject().isResource()) {
            pc.setNodeKind(nodeKindStmt.getObject().asResource());
        }
        // Handle common typo "NodeKind" (capital N)
        Statement nodeKindWrongStmt = propRes.getProperty(shNodeKindWrong);
        if (nodeKindWrongStmt != null && nodeKindWrongStmt.getObject().isResource() && pc.getNodeKind() == null) {
            pc.setNodeKind(nodeKindWrongStmt.getObject().asResource());
        }
        Statement datatypeStmt = propRes.getProperty(shDatatype);
        if (datatypeStmt != null && datatypeStmt.getObject().isResource()) {
            pc.setDatatype(datatypeStmt.getObject().asResource());
        }
        
        // Cardinality constraints (kept as attributes, not symbols)
        parseIntConstraint(propRes, shMinCount, pc::setMinCount);
        parseIntConstraint(propRes, shMaxCount, pc::setMaxCount);
        
        // String length constraints
        parseIntConstraint(propRes, shMinLength, v -> pc.setConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH, v));
        parseIntConstraint(propRes, shMaxLength, v -> pc.setConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH, v));
        
        // Numeric range constraints
        parseNumericConstraint(propRes, shMinInclusive, v -> pc.setConstraint(PropertyConstraint.ConstraintType.MIN_INCLUSIVE, v));
        parseNumericConstraint(propRes, shMaxInclusive, v -> pc.setConstraint(PropertyConstraint.ConstraintType.MAX_INCLUSIVE, v));
        parseNumericConstraint(propRes, shMinExclusive, v -> pc.setConstraint(PropertyConstraint.ConstraintType.MIN_EXCLUSIVE, v));
        parseNumericConstraint(propRes, shMaxExclusive, v -> pc.setConstraint(PropertyConstraint.ConstraintType.MAX_EXCLUSIVE, v));
        
        // Pattern constraint
        Statement patternStmt = propRes.getProperty(shPattern);
        if (patternStmt != null) {
            pc.setConstraint(PropertyConstraint.ConstraintType.PATTERN, patternStmt.getString());
        }
        
        Statement flagsStmt = propRes.getProperty(shFlags);
        if (flagsStmt != null) {
            pc.setConstraint(PropertyConstraint.ConstraintType.FLAGS, flagsStmt.getString());
        }
        
        // Language constraints
        Statement langInStmt = propRes.getProperty(shLanguageIn);
        if (langInStmt != null && langInStmt.getObject().isResource()) {
            List<RDFNode> langValues = parseRDFList(langInStmt.getObject().asResource());
            pc.setConstraint(PropertyConstraint.ConstraintType.LANGUAGE_IN, langValues);
        }
        
        Statement uniqueLangStmt = propRes.getProperty(shUniqueLang);
        if (uniqueLangStmt != null && uniqueLangStmt.getObject().isLiteral()) {
            pc.setConstraint(PropertyConstraint.ConstraintType.UNIQUE_LANG, uniqueLangStmt.getBoolean());
        }
        
        // sh:in constraints - stored both as constraint and as symbols
        Statement inStmt = propRes.getProperty(shIn);
        if (inStmt != null && inStmt.getObject().isResource()) {
            List<RDFNode> inValues = parseRDFList(inStmt.getObject().asResource());
            pc.setConstraint(PropertyConstraint.ConstraintType.IN, inValues);
            // Also create symbols for in-values
            for (RDFNode val : inValues) {
                String sym = symbolTable.assignSymbol("sh:in", val);
                pc.addInSymbol(sym);
            }
        }
        
        // sh:hasValue
        Statement hasValueStmt = propRes.getProperty(shHasValue);
        if (hasValueStmt != null) {
            pc.setConstraint(PropertyConstraint.ConstraintType.HAS_VALUE, hasValueStmt.getObject());
        }
        
        // sh:qualifiedValueShape, sh:qualifiedMinCount, sh:qualifiedMaxCount
        // These form an atomic triplet and are preserved as-is (not simplified)
        Statement qvsStmt = propRes.getProperty(shQualifiedValueShape);
        if (qvsStmt != null && qvsStmt.getObject().isResource()) {
            pc.setConstraint(PropertyConstraint.ConstraintType.QUALIFIED_VALUE_SHAPE, qvsStmt.getObject().asResource());
        }
        parseIntConstraint(propRes, shQualifiedMinCount, v -> pc.setConstraint(PropertyConstraint.ConstraintType.QUALIFIED_MIN_COUNT, v));
        parseIntConstraint(propRes, shQualifiedMaxCount, v -> pc.setConstraint(PropertyConstraint.ConstraintType.QUALIFIED_MAX_COUNT, v));
        Statement qvsdStmt = propRes.getProperty(shQualifiedValueShapesDisjoint);
        if (qvsdStmt != null && qvsdStmt.getObject().isLiteral()) {
            pc.setConstraint(PropertyConstraint.ConstraintType.QUALIFIED_VALUE_SHAPES_DISJOINT, qvsdStmt.getBoolean());
        }
    }
    
    /**
     * Build a logical expression from a property shape's constraints.
     * Returns a ParseResult containing the expression and any side-products
     * (merged count constraints from OR branches, sh:xone alternatives).
     * 
     * ALL constraint types are symbolised here so that cross-level boolean
     * simplification (absorption, distribution) works for any constraint shared
     * across sh:or branches.
     *
     * Dual-track design: every constraint that is also stored as a plain
     * attribute in parsePropertyAttributes() gets symbolised here too.
     * The serializer's removeDualTrackRedundancy() strips any top-level φ
     * factors that duplicate attrs so the same SHACL triple is never emitted
     * twice.
     */
    private ParseResult buildLogicalExpression(Resource propRes) {
        List<LogicalExpression> constraints = new ArrayList<>();
        Integer mergedMinCount = null;
        Integer mergedMaxCount = null;
        List<RDFNode> xoneAlternatives = null;
        
        // ── All single-valued constraints → symbols (dual-track with attrs) ──
        
        // Type/membership constraints
        addSymbolConstraint(propRes, shClass, "sh:class", constraints);
        addSymbolConstraint(propRes, shNode, "sh:node", constraints);
        addSymbolConstraint(propRes, shNodeKind, "sh:nodeKind", constraints);
        addSymbolConstraint(propRes, shDatatype, "sh:datatype", constraints);
        
        // Property pair constraints
        addSymbolConstraint(propRes, shEquals, "sh:equals", constraints);
        addSymbolConstraint(propRes, shDisjoint, "sh:disjoint", constraints);
        addSymbolConstraint(propRes, shLessThan, "sh:lessThan", constraints);
        addSymbolConstraint(propRes, shLessThanOrEquals, "sh:lessThanOrEquals", constraints);
        
        // Value constraints
        addSymbolConstraint(propRes, shHasValue, "sh:hasValue", constraints);
        
        // Cardinality constraints
        addSymbolConstraint(propRes, shMinCount, "sh:minCount", constraints);
        addSymbolConstraint(propRes, shMaxCount, "sh:maxCount", constraints);
        
        // String-length constraints
        addSymbolConstraint(propRes, shMinLength, "sh:minLength", constraints);
        addSymbolConstraint(propRes, shMaxLength, "sh:maxLength", constraints);
        
        // Pattern constraints
        addSymbolConstraint(propRes, shPattern, "sh:pattern", constraints);
        addSymbolConstraint(propRes, shFlags, "sh:flags", constraints);
        
        // Numeric range constraints
        addSymbolConstraint(propRes, shMinInclusive, "sh:minInclusive", constraints);
        addSymbolConstraint(propRes, shMaxInclusive, "sh:maxInclusive", constraints);
        addSymbolConstraint(propRes, shMinExclusive, "sh:minExclusive", constraints);
        addSymbolConstraint(propRes, shMaxExclusive, "sh:maxExclusive", constraints);
        
        // Boolean constraints
        addSymbolConstraint(propRes, shUniqueLang, "sh:uniqueLang", constraints);
        
        // NOTE: sh:in is dual-tracked separately (per-value symbolisation in parsePropertyAttributes)
        // NOTE: sh:languageIn, sh:qualifiedValueShape etc. are list/compound — kept as attrs only
        
        // Parse sh:or -> OrExpr (may produce merged count constraints)
        StmtIterator orStmts = model.listStatements(propRes, shOr, (RDFNode) null);
        while (orStmts.hasNext()) {
            RDFNode orNode = orStmts.next().getObject();
            if (orNode.isResource()) {
                OrParseResult orResult = parseOrConstraint(orNode.asResource());
                if (orResult.expression() != null) {
                    constraints.add(orResult.expression());
                }
                if (orResult.mergedMinCount() != null) mergedMinCount = orResult.mergedMinCount();
                if (orResult.mergedMaxCount() != null) mergedMaxCount = orResult.mergedMaxCount();
            }
        }
        
        // Parse sh:and -> AndExpr
        StmtIterator andStmts = model.listStatements(propRes, shAnd, (RDFNode) null);
        while (andStmts.hasNext()) {
            RDFNode andNode = andStmts.next().getObject();
            if (andNode.isResource()) {
                LogicalExpression andExpr = parseAndConstraint(andNode.asResource());
                if (andExpr != null) {
                    constraints.add(andExpr);
                }
            }
        }
        
        // Parse sh:not -> NotExpr
        StmtIterator notStmts = model.listStatements(propRes, shNot, (RDFNode) null);
        while (notStmts.hasNext()) {
            RDFNode notNode = notStmts.next().getObject();
            if (notNode.isResource()) {
                LogicalExpression notExpr = parseNotConstraint(notNode.asResource());
                if (notExpr != null) {
                    constraints.add(notExpr);
                }
            }
        }
        
        // Parse sh:xone -> stored as opaque attribute (not decomposed into boolean logic)
        StmtIterator xoneStmts = model.listStatements(propRes, shXone, (RDFNode) null);
        while (xoneStmts.hasNext()) {
            RDFNode xoneNode = xoneStmts.next().getObject();
            if (xoneNode.isResource()) {
                List<RDFNode> xoneList = parseRDFList(xoneNode.asResource());
                if (!xoneList.isEmpty()) {
                    xoneAlternatives = xoneList;
                }
            }
        }
        
        // Combine all constraints with AND
        LogicalExpression expression;
        if (constraints.isEmpty()) {
            expression = null;
        } else if (constraints.size() == 1) {
            expression = constraints.get(0);
        } else {
            expression = LogicalExpression.and(constraints);
        }
        
        return new ParseResult(expression, mergedMinCount, mergedMaxCount, xoneAlternatives);
    }
    
    /**
     * Add a symbol constraint to the list if the property exists.
     */
    private void addSymbolConstraint(Resource propRes, Property prop, String constraintType, 
                                     List<LogicalExpression> constraints) {
        StmtIterator stmts = model.listStatements(propRes, prop, (RDFNode) null);
        while (stmts.hasNext()) {
            RDFNode value = stmts.next().getObject();
            LogicalExpression sym = symbolTable.createSymbolExpr(constraintType, value);
            constraints.add(sym);
        }
    }
    
    /**
     * Helper class to hold OR branch data including both logical expression and count constraints.
     */
    private static class OrBranch {
        final LogicalExpression logicalExpr;
        final Integer minCount;
        final Integer maxCount;
        
        OrBranch(LogicalExpression logicalExpr, Integer minCount, Integer maxCount) {
            this.logicalExpr = logicalExpr;
            this.minCount = minCount;
            this.maxCount = maxCount;
        }
        
        boolean hasCountConstraints() {
            return minCount != null || maxCount != null;
        }
    }
    
    /**
     * Value object returned by parseOrConstraint carrying both the logical expression
     * and any merged count constraints from OR branches.
     */
    record OrParseResult(LogicalExpression expression, Integer mergedMinCount, Integer mergedMaxCount) {}

    /**
     * Parse sh:or constraint into an OrExpr.
     * sh:or is an RDF list of constraint shapes.
     * 
     * Optimization: If OR branches have the same logical constraints but different
     * count constraints, merge them using OR semantics:
     * - minCount: take the minimum (looser constraint)
     * - maxCount: take the maximum (looser constraint)
     */
    private OrParseResult parseOrConstraint(Resource listRes) {
        List<RDFNode> branches = parseRDFList(listRes);
        if (branches.isEmpty()) return new OrParseResult(null, null, null);
        
        // Parse all branches with their count constraints
        List<OrBranch> orBranches = new ArrayList<>();
        for (RDFNode branch : branches) {
            if (branch.isResource()) {
                Resource branchRes = branch.asResource();
                LogicalExpression branchExpr = parseBranchConstraints(branchRes);
                
                // Parse count constraints from the branch
                Integer minCount = parseIntValue(branchRes, shMinCount);
                Integer maxCount = parseIntValue(branchRes, shMaxCount);
                
                if (branchExpr != null || minCount != null || maxCount != null) {
                    orBranches.add(new OrBranch(branchExpr, minCount, maxCount));
                }
            }
        }
        
        if (orBranches.isEmpty()) return new OrParseResult(null, null, null);
        if (orBranches.size() == 1) {
            return new OrParseResult(orBranches.get(0).logicalExpr, null, null);
        }
        
        // Try to merge branches with same logical expression but different counts
        orBranches = mergeOrBranchesWithCounts(orBranches);
        
        // If all branches merged into one, propagate the merged counts
        Integer mergedMinCount = null;
        Integer mergedMaxCount = null;
        if (orBranches.size() == 1 && orBranches.get(0).hasCountConstraints()) {
            OrBranch merged = orBranches.get(0);
            mergedMinCount = merged.minCount;
            mergedMaxCount = merged.maxCount;
            log.debug("Storing merged OR counts: minCount={}, maxCount={}", 
                     mergedMinCount, mergedMaxCount);
        }
        
        // Build the final OR expression
        List<LogicalExpression> branchExprs = new ArrayList<>();
        for (OrBranch branch : orBranches) {
            if (branch.logicalExpr != null) {
                branchExprs.add(branch.logicalExpr);
            }
        }
        
        LogicalExpression expression;
        if (branchExprs.isEmpty()) {
            expression = null;
        } else if (branchExprs.size() == 1) {
            expression = branchExprs.get(0);
        } else {
            if (branchExprs.size() > 100) {
                long symCount = branchExprs.stream().filter(e -> e instanceof SymbolExpr).count();
                long andCount = branchExprs.stream().filter(e -> e instanceof AndExpr).count();
                log.info("parseOrConstraint: {} branches ({} Symbol, {} AND) after merge from {} raw",
                    branchExprs.size(), symCount, andCount, branches.size());
            }
            expression = new OrExpr(branchExprs);
        }
        
        return new OrParseResult(expression, mergedMinCount, mergedMaxCount);
    }
    
    /**
     * Merge OR branches that have the same logical constraints but different count constraints.
     * Uses OR semantics: min(minCount), max(maxCount).
     * 
     * Note: Count constraints on OR branches are currently not preserved in the final
     * PropertyConstraint. This merging reduces redundant logical expressions.
     */
    private List<OrBranch> mergeOrBranchesWithCounts(List<OrBranch> branches) {
        if (branches.size() <= 1) return branches;
        
        // Group branches by their logical expression, preserving insertion order
        Map<String, List<OrBranch>> groupedBranches = new LinkedHashMap<>();
        for (OrBranch branch : branches) {
            String key = branch.logicalExpr == null ? "null" : branch.logicalExpr.toString();
            groupedBranches.computeIfAbsent(key, k -> new ArrayList<>()).add(branch);
        }
        
        List<OrBranch> mergedBranches = new ArrayList<>();
        for (List<OrBranch> group : groupedBranches.values()) {
            if (group.size() == 1) {
                // No merging needed
                mergedBranches.add(group.get(0));
            } else {
                // Merge branches with same logical expression
                // OR semantics: min(minCount), max(maxCount)
                LogicalExpression logicalExpr = group.get(0).logicalExpr;
                Integer mergedMinCount = null;
                Integer mergedMaxCount = null;
                
                for (OrBranch branch : group) {
                    if (branch.minCount != null) {
                        mergedMinCount = (mergedMinCount == null) ? branch.minCount 
                                       : Math.min(mergedMinCount, branch.minCount);
                    }
                    if (branch.maxCount != null) {
                        mergedMaxCount = (mergedMaxCount == null) ? branch.maxCount 
                                       : Math.max(mergedMaxCount, branch.maxCount);
                    }
                }
                
                mergedBranches.add(new OrBranch(logicalExpr, mergedMinCount, mergedMaxCount));
                
                log.debug("Merged {} OR branches with same logical expression: {}", 
                         group.size(), logicalExpr);
                log.debug("  Count constraints merged: minCount={}, maxCount={}", 
                         mergedMinCount, mergedMaxCount);
            }
        }
        
        return mergedBranches;
    }
    
    /**
     * Parse an integer value from a property.
     */
    private Integer parseIntValue(Resource res, Property prop) {
        Statement stmt = res.getProperty(prop);
        if (stmt != null && stmt.getObject().isLiteral()) {
            return stmt.getInt();
        }
        return null;
    }
    
    /**
     * Parse sh:and constraint into an AndExpr.
     */
    private LogicalExpression parseAndConstraint(Resource listRes) {
        List<RDFNode> branches = parseRDFList(listRes);
        if (branches.isEmpty()) return null;
        
        List<LogicalExpression> branchExprs = new ArrayList<>();
        for (RDFNode branch : branches) {
            if (branch.isResource()) {
                LogicalExpression branchExpr = parseBranchConstraints(branch.asResource());
                if (branchExpr != null) {
                    branchExprs.add(branchExpr);
                }
            }
        }
        
        if (branchExprs.isEmpty()) return null;
        if (branchExprs.size() == 1) return branchExprs.get(0);
        return new AndExpr(branchExprs);
    }
    
    /**
     * Parse sh:not constraint into a NotExpr.
     */
    private LogicalExpression parseNotConstraint(Resource notRes) {
        LogicalExpression inner = parseBranchConstraints(notRes);
        if (inner == null) return null;
        return new NotExpr(inner);
    }
    
    /**
     * Parse constraints from a branch (used in sh:or, sh:and, sh:not).
     * This recursively builds the logical expression for nested structures.
     */
    private LogicalExpression parseBranchConstraints(Resource branchRes) {
        List<LogicalExpression> constraints = new ArrayList<>();
        
        // Type constraints
        addSymbolConstraint(branchRes, shNodeKind, "sh:nodeKind", constraints);
        addSymbolConstraint(branchRes, shNodeKindWrong, "sh:nodeKind", constraints);
        addSymbolConstraint(branchRes, shClass, "sh:class", constraints);
        addSymbolConstraint(branchRes, shDatatype, "sh:datatype", constraints);
        addSymbolConstraint(branchRes, shNode, "sh:node", constraints);
        
        // Property pair constraints
        addSymbolConstraint(branchRes, shEquals, "sh:equals", constraints);
        addSymbolConstraint(branchRes, shDisjoint, "sh:disjoint", constraints);
        addSymbolConstraint(branchRes, shLessThan, "sh:lessThan", constraints);
        addSymbolConstraint(branchRes, shLessThanOrEquals, "sh:lessThanOrEquals", constraints);
        
        // sh:hasValue
        Statement hasValueStmt = branchRes.getProperty(shHasValue);
        if (hasValueStmt != null) {
            LogicalExpression sym = symbolTable.createSymbolExpr("sh:hasValue", hasValueStmt.getObject());
            constraints.add(sym);
        }
        
        // sh:in - In OR branches, each sh:in value becomes a symbol in the logical expression
        // This matches Python behavior where in_symbols are converted to logical constraints in OR context
        Statement inStmt = branchRes.getProperty(shIn);
        if (inStmt != null && inStmt.getObject().isResource()) {
            List<RDFNode> inValues = parseRDFList(inStmt.getObject().asResource());
            for (RDFNode val : inValues) {
                LogicalExpression sym = symbolTable.createSymbolExpr("sh:in", val);
                constraints.add(sym);
            }
        }
        
        // Nested sh:or
        StmtIterator orStmts = model.listStatements(branchRes, shOr, (RDFNode) null);
        while (orStmts.hasNext()) {
            RDFNode orNode = orStmts.next().getObject();
            if (orNode.isResource()) {
                OrParseResult orResult = parseOrConstraint(orNode.asResource());
                if (orResult.expression() != null) constraints.add(orResult.expression());
            }
        }
        
        // Nested sh:and
        StmtIterator andStmts = model.listStatements(branchRes, shAnd, (RDFNode) null);
        while (andStmts.hasNext()) {
            RDFNode andNode = andStmts.next().getObject();
            if (andNode.isResource()) {
                LogicalExpression andExpr = parseAndConstraint(andNode.asResource());
                if (andExpr != null) constraints.add(andExpr);
            }
        }
        
        // Nested sh:not
        StmtIterator notStmts = model.listStatements(branchRes, shNot, (RDFNode) null);
        while (notStmts.hasNext()) {
            RDFNode notNode = notStmts.next().getObject();
            if (notNode.isResource()) {
                LogicalExpression notExpr = parseNotConstraint(notNode.asResource());
                if (notExpr != null) constraints.add(notExpr);
            }
        }
        
        // Nested sh:xone -> treat as opaque symbol
        StmtIterator xoneStmts = model.listStatements(branchRes, shXone, (RDFNode) null);
        while (xoneStmts.hasNext()) {
            RDFNode xoneNode = xoneStmts.next().getObject();
            if (xoneNode.isResource()) {
                // Create a unique symbol for this xone occurrence (keyed by RDF list identity)
                LogicalExpression sym = symbolTable.createSymbolExpr("sh:xone", xoneNode);
                constraints.add(sym);
            }
        }
        
        if (constraints.isEmpty()) return null;
        if (constraints.size() == 1) return constraints.get(0);
        return new AndExpr(constraints);
    }
    
    /**
     * Parse node-level logical constraints (sh:and, sh:or, sh:not at NodeShape level).
     */
    private void parseNodeLogicalConstraints(Resource shapeRes, NodeShape shape) {
        parseNodeLogicalConstraint(shapeRes, shAnd, LogicalConstraint.LogicalType.AND, shape);
        parseNodeLogicalConstraint(shapeRes, shOr, LogicalConstraint.LogicalType.OR, shape);
        parseNodeLogicalConstraint(shapeRes, shNot, LogicalConstraint.LogicalType.NOT, shape);
        parseNodeLogicalConstraint(shapeRes, shXone, LogicalConstraint.LogicalType.XONE, shape);
    }
    
    private void parseNodeLogicalConstraint(Resource shapeRes, Property prop, 
                                            LogicalConstraint.LogicalType type, NodeShape shape) {
        StmtIterator stmts = model.listStatements(shapeRes, prop, (RDFNode) null);
        while (stmts.hasNext()) {
            RDFNode node = stmts.next().getObject();
            LogicalConstraint lc = new LogicalConstraint(type);
            
            if (type == LogicalConstraint.LogicalType.NOT) {
                if (node.isResource()) lc.addOperand(node.asResource());
            } else {
                if (node.isResource()) {
                    List<RDFNode> operands = parseRDFList(node.asResource());
                    for (RDFNode operand : operands) {
                        if (operand.isResource()) lc.addOperand(operand.asResource());
                    }
                }
            }
            if (lc.getOperandCount() > 0) shape.addLogicalConstraint(lc);
        }
    }
    
    private void parseIntConstraint(Resource res, Property prop, java.util.function.Consumer<Integer> setter) {
        Statement stmt = res.getProperty(prop);
        if (stmt != null) {
            try { setter.accept(stmt.getInt()); } catch (Exception e) { /* ignore */ }
        }
    }
    
    private void parseNumericConstraint(Resource res, Property prop, java.util.function.Consumer<Number> setter) {
        Statement stmt = res.getProperty(prop);
        if (stmt != null && stmt.getObject().isLiteral()) {
            try {
                Object value = stmt.getLiteral().getValue();
                if (value instanceof Number num) setter.accept(num);
            } catch (Exception e) { /* ignore */ }
        }
    }
    
    /** Maximum iterations for parseRDFList to prevent infinite loops on malformed data */
    private static final int RDF_LIST_MAX_ITERATIONS = 100_000;
    
    private List<RDFNode> parseRDFList(Resource listRes) {
        List<RDFNode> result = new ArrayList<>();
        Resource current = listRes;
        int iterations = 0;
        
        while (current != null && !current.equals(RDF.nil) && iterations < RDF_LIST_MAX_ITERATIONS) {
            Statement firstStmt = current.getProperty(RDF.first);
            if (firstStmt != null) result.add(firstStmt.getObject());
            
            Statement restStmt = current.getProperty(RDF.rest);
            if (restStmt != null && restStmt.getObject().isResource()) {
                current = restStmt.getObject().asResource();
            } else {
                break;
            }
            iterations++;
        }
        return result;
    }
}
