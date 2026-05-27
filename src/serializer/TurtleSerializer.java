package serializer;

import model.*;
import logic.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Serializes a ShapeGraph to RDF format (Turtle, N-Triples, etc.).
 * 
 * This serializer converts LogicalExpression back to SHACL constraints:
 * - SymbolExpr -> looks up in SymbolTable to get constraint type and value
 * - OrExpr -> sh:or (RDF list of branches)
 * - AndExpr -> sh:and (RDF list of branches) or multiple direct properties
 * - NotExpr -> sh:not
 * 
 * Supported output formats:
 * - NTRIPLES (default): fastest write, ~105ms for 220K triples
 * - TURTLE_FLAT: compact, ~216ms
 * - TURTLE_PRETTY: human-readable, ~430ms
 * - TURTLE_BLOCKS: grouped, ~480ms
 * 
 * All formats are fully compatible with Jena SHACL validation.
 */
public class TurtleSerializer {
    private static final Logger log = LoggerFactory.getLogger(TurtleSerializer.class);
    private static final String SH = ShaclVocabulary.SH;
    
    private Model model;
    private Model sourceModel;  // Original model for deep-copying blank nodes
    private SymbolTable symbolTable;
    
    // Output format - NTRIPLES is ~4x faster than TURTLE_PRETTY
    private RDFFormat outputFormat = RDFFormat.NTRIPLES;
    
    // Batch size for streaming output (shapes per batch)
    private int streamingBatchSize = 100;
    
    /**
     * Set the RDF output format.
     * Recommended: NTRIPLES (fastest), TURTLE_FLAT (compact + readable), TURTLE_PRETTY (human-readable).
     */
    public TurtleSerializer setOutputFormat(RDFFormat format) {
        this.outputFormat = format;
        return this;
    }
    
    /**
     * Get the current output format.
     */
    public RDFFormat getOutputFormat() {
        return outputFormat;
    }
    
    /**
     * Set the batch size for streaming output.
     * Smaller batches use less memory but may be slower.
     */
    public TurtleSerializer setStreamingBatchSize(int batchSize) {
        this.streamingBatchSize = batchSize > 0 ? batchSize : 100;
        return this;
    }
    
    private static final int BUFFER_SIZE = 64 * 1024;
    
    public void serialize(ShapeGraph shapeGraph, Path outputPath) throws IOException {
        log.info("Serializing ShapeGraph to {} (format: {})", outputPath, outputFormat);
        long startTime = System.currentTimeMillis();
        
        buildModelInternal(shapeGraph);
        
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outputPath.toFile()), BUFFER_SIZE)) {
            RDFDataMgr.write(out, model, outputFormat);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Serialization completed in {}ms, model has {} triples (format: {})", duration, model.size(), outputFormat);
    }
    
    /**
     * Build the internal Jena Model from a ShapeGraph without writing to file.
     * Useful when you need the Model for further processing (e.g., SHACL validation).
     */
    public Model buildModel(ShapeGraph shapeGraph) {
        buildModelInternal(shapeGraph);
        return model;
    }
    
    /**
     * Stream serialize to output file with batched writing.
     * This is more memory efficient for large shape graphs.
     * 
     * The output writes shapes in batches, flushing to disk periodically
     * to avoid building up a huge in-memory model.
     */
    public void serializeStreaming(ShapeGraph shapeGraph, Path outputPath) throws IOException {
        log.info("Streaming ShapeGraph to {} (batch size: {})", outputPath, streamingBatchSize);
        long startTime = System.currentTimeMillis();
        
        symbolTable = shapeGraph.getSymbolTable();
        sourceModel = shapeGraph.getSourceModel();
        List<NodeShape> shapes = new ArrayList<>(shapeGraph.getNodeShapes());
        int totalShapes = shapes.size();
        long totalTriples = 0;
        
        try (BufferedOutputStream out = new BufferedOutputStream(
                new FileOutputStream(outputPath.toFile()), BUFFER_SIZE)) {
            
            // Write prefixes once
            writePrefixes(out);
            
            // Process shapes in batches
            for (int i = 0; i < totalShapes; i += streamingBatchSize) {
                int end = Math.min(i + streamingBatchSize, totalShapes);
                List<NodeShape> batch = shapes.subList(i, end);
                
                // Create a fresh model for this batch
                model = ModelFactory.createDefaultModel();
                setupNamespaces();
                
                for (NodeShape shape : batch) {
                    serializeNodeShape(shape);
                }
                
                totalTriples += model.size();
                
                // Write batch to output (without prefixes, they were already written)
                StringWriter sw = new StringWriter();
                RDFDataMgr.write(sw, model, RDFFormat.TURTLE_BLOCKS);
                String content = sw.toString();
                
                // Skip prefix declarations (they're at the start)
                int dataStart = findDataStart(content);
                if (dataStart > 0) {
                    content = content.substring(dataStart);
                }
                
                out.write(content.getBytes("UTF-8"));
                out.write('\n');
                
                // Free memory
                model = null;
                
                if ((end % 1000 == 0 || end == totalShapes) && totalShapes > streamingBatchSize) {
                    log.info("Streaming progress: {}/{} shapes", end, totalShapes);
                }
            }
            
            out.flush();
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Streaming serialization completed in {}ms, ~{} triples", duration, totalTriples);
    }
    
    private void writePrefixes(OutputStream out) throws IOException {
        StringBuilder prefixes = new StringBuilder();
        prefixes.append("@prefix sh:   <http://www.w3.org/ns/shacl#> .\n");
        prefixes.append("@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n");
        prefixes.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        prefixes.append("@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n\n");
        out.write(prefixes.toString().getBytes("UTF-8"));
    }
    
    private int findDataStart(String content) {
        // Find where actual data starts (after all @prefix lines)
        int pos = 0;
        while (true) {
            int nextLine = content.indexOf('\n', pos);
            if (nextLine < 0) break;
            
            String line = content.substring(pos, nextLine).trim();
            if (!line.startsWith("@prefix") && !line.isEmpty()) {
                return pos;
            }
            pos = nextLine + 1;
        }
        return 0;
    }
    
    public String serializeToString(ShapeGraph shapeGraph) {
        buildModelInternal(shapeGraph);
        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, outputFormat);
        return writer.toString();
    }
    
    /**
     * Internal helper: initializes shared state and populates the Jena Model from a ShapeGraph.
     * Called by serialize(), buildModel(), serializeToString(), and (per-batch) serializeStreaming().
     */
    private void buildModelInternal(ShapeGraph shapeGraph) {
        model = ModelFactory.createDefaultModel();
        sourceModel = shapeGraph.getSourceModel();
        symbolTable = shapeGraph.getSymbolTable();
        setupNamespaces();
        for (NodeShape shape : shapeGraph.getNodeShapes()) {
            serializeNodeShape(shape);
        }
    }
    
    private void setupNamespaces() {
        model.setNsPrefix("sh", SH);
        model.setNsPrefix("rdf", RDF.getURI());
        model.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        model.setNsPrefix("xsd", "http://www.w3.org/2001/XMLSchema#");
    }
    
    private void serializeNodeShape(NodeShape shape) {
        Resource shapeRes = model.createResource(shape.getUri());
        Property shaclType = model.createProperty(SH, "NodeShape");
        shapeRes.addProperty(RDF.type, shaclType);
        
        serializeTargets(shapeRes, shape);
        
        for (PropertyConstraint pc : shape.getPropertyConstraints()) {
            serializePropertyConstraint(shapeRes, pc);
        }
        
        for (LogicalConstraint lc : shape.getLogicalConstraints()) {
            serializeLogicalConstraint(shapeRes, lc);
        }
        
        // Serialize sh:hasClass (non-standard magic seed)
        if (shape.getHasClass() != null) {
            Property hasClassProp = model.createProperty(SH, "hasClass");
            shapeRes.addProperty(hasClassProp, shape.getHasClass());
        }

        // Serialize sh:node at NodeShape level (pass-through)
        if (!shape.getNodeRefs().isEmpty()) {
            Property nodeProp = model.createProperty(SH, "node");
            for (Resource ref : shape.getNodeRefs()) {
                shapeRes.addProperty(nodeProp, ref);
            }
        }

        // Serialize sh:closed and sh:ignoredProperties
        if (shape.hasClosedConstraint()) {
            Property closedProp = model.createProperty(SH, "closed");
            shapeRes.addLiteral(closedProp, shape.isClosed());
            
            if (!shape.getIgnoredProperties().isEmpty()) {
                Property ignoredProp = model.createProperty(SH, "ignoredProperties");
                RDFList rdfList = model.createList(
                    shape.getIgnoredProperties().stream()
                        .map(r -> (RDFNode) r)
                        .iterator()
                );
                shapeRes.addProperty(ignoredProp, rdfList);
            }
        }
    }
    
    private void serializeTargets(Resource shapeRes, NodeShape shape) {
        Property targetClass = model.createProperty(SH, "targetClass");
        Property targetNode = model.createProperty(SH, "targetNode");
        Property targetSubjectsOf = model.createProperty(SH, "targetSubjectsOf");
        Property targetObjectsOf = model.createProperty(SH, "targetObjectsOf");
        
        for (Resource tc : shape.getTargetClasses()) shapeRes.addProperty(targetClass, tc);
        for (Resource tn : shape.getTargetNodes()) shapeRes.addProperty(targetNode, tn);
        for (Resource ts : shape.getTargetSubjectsOf()) shapeRes.addProperty(targetSubjectsOf, ts);
        for (Resource to : shape.getTargetObjectsOf()) shapeRes.addProperty(targetObjectsOf, to);
    }
    
    private void serializePropertyConstraint(Resource shapeRes, PropertyConstraint pc) {
        Resource propShape = pc.hasUri() ? model.createResource(pc.getUri()) : model.createResource();
        
        Property shaclPropType = model.createProperty(SH, "PropertyShape");
        propShape.addProperty(RDF.type, shaclPropType);
        Property shProperty = model.createProperty(SH, "property");
        shapeRes.addProperty(shProperty, propShape);
        
        // Serialize path
        if (pc.getPath() != null) {
            Property pathProp = model.createProperty(SH, "path");
            propShape.addProperty(pathProp, pc.getPath());
        }
        
        // Serialize attribute constraints (minCount, maxCount, etc.)
        Map<PropertyConstraint.ConstraintType, Object> constraints = pc.getConstraints();
        for (Map.Entry<PropertyConstraint.ConstraintType, Object> entry : constraints.entrySet()) {
            // Skip OR type - it's handled by logical expression
            if (entry.getKey() != PropertyConstraint.ConstraintType.OR) {
                serializeConstraintValue(propShape, entry.getKey(), entry.getValue());
            }
        }
        
        // Serialize logical expression (use simplified version, not original)
        // Post-processing: remove top-level φ factors that duplicate attrs (dual-track dedup)
        LogicalExpression logicalExpr = removeDualTrackRedundancy(pc.getLogicalExpression(), constraints);
        if (logicalExpr != null) {
            serializeLogicalExpression(propShape, logicalExpr);
        }
    }
    
    /**
     * Remove top-level AND conjuncts from φ that are redundant with plain attributes.
     * 
     * This handles the universal dual-track design: every single-valued constraint
     * is stored as both a plain attribute (for merge semantics) and a symbol (for
     * cross-level simplification).  After boolean simplification, a top-level symbol
     * like nk_1 (sh:nodeKind sh:IRI) or minc_5 (sh:minCount 1) may remain in φ
     * even though attrs already has the same value.  We strip such duplicates here
     * to avoid emitting the same SHACL triple twice.
     *
     * @param expr the logical expression (may be null)
     * @param attrs the constraint attributes map
     * @return the cleaned expression, or null if nothing remains
     */
    private LogicalExpression removeDualTrackRedundancy(
            LogicalExpression expr, Map<PropertyConstraint.ConstraintType, Object> attrs) {
        if (expr == null || symbolTable == null || attrs.isEmpty()) return expr;
        
        // Determine which symbols to remove: any symbol whose (type,value) matches an attr
        java.util.function.Predicate<SymbolExpr> isRedundant = sym -> {
            SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
            if (info == null) return false;
            String type = info.constraintType();
            RDFNode symValue = info.constraintValue();
            PropertyConstraint.ConstraintType ct = symbolTypeToConstraintType(type);
            if (ct == null) return false;
            Object attrValue = attrs.get(ct);
            if (attrValue == null) return false;
            return valuesMatch(symValue, attrValue);
        };
        
        // Case 1: φ is a single redundant symbol → remove entirely
        if (expr instanceof SymbolExpr sym && isRedundant.test(sym)) {
            return null;
        }
        
        // Case 2: φ is AND(a, b, c, ...) → filter out redundant symbols
        if (expr instanceof AndExpr and) {
            List<LogicalExpression> kept = new ArrayList<>();
            for (LogicalExpression arg : and.getArgs()) {
                if (arg instanceof SymbolExpr sym && isRedundant.test(sym)) {
                    continue; // skip redundant
                }
                kept.add(arg);
            }
            if (kept.size() == and.getArgs().size()) return expr; // nothing removed
            if (kept.isEmpty()) return null;
            if (kept.size() == 1) return kept.get(0);
            return LogicalExpression.and(kept);
        }
        
        // Other cases (Or, Not, etc.) — no top-level redundancy to remove
        return expr;
    }
    
    /** Map symbol constraint type strings to ConstraintType enum. */
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
    
    /**
     * Compare a symbol's RDFNode value against an attr's Java object value.
     * Handles type mismatches: symbol always stores RDFNode, but attrs may store
     * Integer, String, Boolean, Resource, RDFNode, etc.
     */
    private static boolean valuesMatch(RDFNode symValue, Object attrValue) {
        if (symValue == null || attrValue == null) return false;
        
        // Both are RDFNode (e.g., hasValue)
        if (attrValue instanceof RDFNode attrNode) {
            return symValue.equals(attrNode);
        }
        // Attr is Resource (e.g., nodeKind, datatype, class, node)
        if (attrValue instanceof Resource r) {
            return symValue.isResource() && symValue.asResource().getURI().equals(r.getURI());
        }
        // Attr is Integer (e.g., minCount, maxCount, minLength, maxLength)
        if (attrValue instanceof Integer intVal && symValue.isLiteral()) {
            try {
                return symValue.asLiteral().getInt() == intVal;
            } catch (Exception e) {
                return false;
            }
        }
        // Attr is Number (e.g., min/maxInclusive/Exclusive)
        if (attrValue instanceof Number numVal && symValue.isLiteral()) {
            try {
                return Double.compare(symValue.asLiteral().getDouble(), numVal.doubleValue()) == 0;
            } catch (Exception e) {
                return false;
            }
        }
        // Attr is String (e.g., pattern, flags)
        if (attrValue instanceof String strVal && symValue.isLiteral()) {
            return strVal.equals(symValue.asLiteral().getString());
        }
        // Attr is Boolean (e.g., uniqueLang)
        if (attrValue instanceof Boolean boolVal && symValue.isLiteral()) {
            try {
                return boolVal == symValue.asLiteral().getBoolean();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
    
    /**
     * Serialize a logical expression to SHACL constraints on a property shape.
     */
    private void serializeLogicalExpression(Resource propShape, LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            serializeSymbol(propShape, sym);
        } else if (expr instanceof OrExpr or) {
            serializeOrExpression(propShape, or);
        } else if (expr instanceof AndExpr and) {
            // For And at top level, serialize each part directly
            serializeAndExpression(propShape, and);
        } else if (expr instanceof NotExpr not) {
            serializeNotExpression(propShape, not);
        }
    }
    
    /**
     * Serialize a symbol by looking up its constraint type and value.
     */
    private void serializeSymbol(Resource target, SymbolExpr sym) {
        if (symbolTable == null) {
            log.warn("No symbol table available for serialization");
            return;
        }
        
        SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(sym.getName());
        if (info == null) {
            log.warn("Symbol {} not found in symbol table", sym.getName());
            return;
        }
        
        String constraintType = info.constraintType();
        RDFNode constraintValue = info.constraintValue();
        
        // Map constraint type string to property
        String propName = constraintTypeToProperty(constraintType);
        if (propName == null) {
            log.warn("Unknown constraint type: {}", constraintType);
            return;
        }
        
        Property prop = model.createProperty(SH, propName);
        
        // sh:xone inside branches requires special RDF list reconstruction
        if ("sh:xone".equals(constraintType) && constraintValue.isResource()) {
            serializeXoneSymbol(target, prop, constraintValue.asResource());
            return;
        }
        
        target.addProperty(prop, constraintValue);
    }
    
    /**
     * Serialize an sh:xone symbol by reconstructing the RDF list in the serialization model.
     * The constraintValue is the RDF list head node from the source model;
     * we deep-copy each list item into this model's blank node space.
     */
    private void serializeXoneSymbol(Resource target, Property prop, Resource listHead) {
        Model srcModel = sourceModel != null ? sourceModel : listHead.getModel();
        List<RDFNode> items = new ArrayList<>();
        Resource current = listHead;
        while (current != null && !current.equals(RDF.nil)) {
            Statement firstStmt = current.getProperty(RDF.first);
            if (firstStmt != null) {
                items.add(deepCopyNode(firstStmt.getObject(), srcModel));
            }
            Statement restStmt = current.getProperty(RDF.rest);
            if (restStmt != null && restStmt.getObject().isResource()) {
                current = restStmt.getObject().asResource();
            } else break;
        }
        if (!items.isEmpty()) {
            RDFList rdfList = model.createList(items.iterator());
            target.addProperty(prop, rdfList);
        }
    }
    
    /**
     * Serialize an Or expression as sh:or.
     */
    private void serializeOrExpression(Resource target, OrExpr or) {
        Property shOr = model.createProperty(SH, "or");
        
        List<RDFNode> branchNodes = new ArrayList<>();
        for (LogicalExpression arg : or.getArgs()) {
            Resource branch = model.createResource();
            serializeExpressionToBranch(branch, arg);
            branchNodes.add(branch);
        }
        
        RDFList rdfList = model.createList(branchNodes.iterator());
        target.addProperty(shOr, rdfList);
    }
    
    /**
     * Serialize an And expression.
     * If at top level of property shape, serialize each part directly.
     */
    private void serializeAndExpression(Resource target, AndExpr and) {
        // At top level, just serialize each part directly to the property shape
        for (LogicalExpression arg : and.getArgs()) {
            if (arg instanceof SymbolExpr sym) {
                serializeSymbol(target, sym);
            } else if (arg instanceof OrExpr or) {
                serializeOrExpression(target, or);
            } else if (arg instanceof NotExpr not) {
                serializeNotExpression(target, not);
            } else if (arg instanceof AndExpr nestedAnd) {
                // Flatten nested And
                serializeAndExpression(target, nestedAnd);
            }
        }
    }
    
    /**
     * Serialize a Not expression as sh:not.
     */
    private void serializeNotExpression(Resource target, NotExpr not) {
        Property shNot = model.createProperty(SH, "not");
        Resource inner = model.createResource();
        serializeExpressionToBranch(inner, not.getArg());
        target.addProperty(shNot, inner);
    }
    
    /**
     * Serialize an expression to a branch resource (used inside sh:or, sh:and, sh:not).
     */
    private void serializeExpressionToBranch(Resource branch, LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            serializeSymbol(branch, sym);
        } else if (expr instanceof AndExpr and) {
            // Inside a branch, And means multiple constraints on same blank node
            for (LogicalExpression arg : and.getArgs()) {
                serializeExpressionToBranch(branch, arg);
            }
        } else if (expr instanceof OrExpr or) {
            // Nested or inside a branch
            serializeOrExpression(branch, or);
        } else if (expr instanceof NotExpr not) {
            serializeNotExpression(branch, not);
        }
    }
    
    /**
     * Map constraint type string to SHACL property name.
     */
    private String constraintTypeToProperty(String constraintType) {
        return switch (constraintType) {
            case "sh:nodeKind" -> "nodeKind";
            case "sh:class" -> "class";
            case "sh:datatype" -> "datatype";
            case "sh:node" -> "node";
            case "sh:hasValue" -> "hasValue";
            case "sh:in" -> "in";
            case "sh:equals" -> "equals";
            case "sh:disjoint" -> "disjoint";
            case "sh:lessThan" -> "lessThan";
            case "sh:lessThanOrEquals" -> "lessThanOrEquals";
            case "sh:xone" -> "xone";
            case "sh:pattern" -> "pattern";
            case "sh:minCount" -> "minCount";
            case "sh:maxCount" -> "maxCount";
            default -> {
                log.warn("Unhandled constraint type: {}", constraintType);
                yield null;
            }
        };
    }
    
    private void serializeConstraintValue(Resource propShape, PropertyConstraint.ConstraintType type, Object value) {
        String propName = getPropertyName(type);
        Property prop = model.createProperty(SH, propName);
        
        if (type == PropertyConstraint.ConstraintType.MIN_COUNT || 
            type == PropertyConstraint.ConstraintType.MAX_COUNT ||
            type == PropertyConstraint.ConstraintType.MIN_LENGTH ||
            type == PropertyConstraint.ConstraintType.MAX_LENGTH) {
            if (value instanceof Number num) {
                // Use xsd:integer to match SHACL spec and preserve original datatype
                Literal lit = ResourceFactory.createTypedLiteral(
                    String.valueOf(num.intValue()), XSDDatatype.XSDinteger);
                propShape.addLiteral(prop, lit);
            }
        } else if (type == PropertyConstraint.ConstraintType.MIN_EXCLUSIVE ||
                   type == PropertyConstraint.ConstraintType.MAX_EXCLUSIVE ||
                   type == PropertyConstraint.ConstraintType.MIN_INCLUSIVE ||
                   type == PropertyConstraint.ConstraintType.MAX_INCLUSIVE) {
            if (value instanceof Number num) propShape.addLiteral(prop, num);
        } else if (type == PropertyConstraint.ConstraintType.DATATYPE ||
                   type == PropertyConstraint.ConstraintType.CLASS ||
                   type == PropertyConstraint.ConstraintType.NODE_KIND ||
                   type == PropertyConstraint.ConstraintType.NODE) {
            if (value instanceof Resource res) propShape.addProperty(prop, res);
            else if (value instanceof String str) propShape.addProperty(prop, model.createResource(str));
        } else if (type == PropertyConstraint.ConstraintType.PATTERN ||
                   type == PropertyConstraint.ConstraintType.FLAGS) {
            if (value instanceof String str) propShape.addProperty(prop, str);
        } else if (type == PropertyConstraint.ConstraintType.HAS_VALUE) {
            if (value instanceof RDFNode node) propShape.addProperty(prop, node);
            else if (value instanceof String str) propShape.addProperty(prop, str);
        } else if (type == PropertyConstraint.ConstraintType.IN ||
                   type == PropertyConstraint.ConstraintType.LANGUAGE_IN) {
            if (value instanceof List<?> list) {
                RDFList rdfList = createRDFList(list);
                propShape.addProperty(prop, rdfList);
            }
        } else if (type == PropertyConstraint.ConstraintType.XONE) {
            if (value instanceof List<?> list) {
                // XONE list items may be blank nodes from the source model — deep-copy them
                RDFList rdfList = sourceModel != null ? createRDFListDeepCopy(list, sourceModel) : createRDFList(list);
                propShape.addProperty(prop, rdfList);
            }
        } else if (type == PropertyConstraint.ConstraintType.UNIQUE_LANG ||
                   type == PropertyConstraint.ConstraintType.CLOSED ||
                   type == PropertyConstraint.ConstraintType.QUALIFIED_VALUE_SHAPES_DISJOINT) {
            if (value instanceof Boolean bool) propShape.addLiteral(prop, bool);
        } else if (type == PropertyConstraint.ConstraintType.QUALIFIED_VALUE_SHAPE) {
            // sh:qualifiedValueShape points to a shape (Resource)
            if (value instanceof Resource res) propShape.addProperty(prop, res);
            else if (value instanceof String str) propShape.addProperty(prop, model.createResource(str));
        } else if (type == PropertyConstraint.ConstraintType.QUALIFIED_MIN_COUNT ||
                   type == PropertyConstraint.ConstraintType.QUALIFIED_MAX_COUNT) {
            if (value instanceof Number num) {
                Literal lit = ResourceFactory.createTypedLiteral(
                    String.valueOf(num.intValue()), XSDDatatype.XSDinteger);
                propShape.addLiteral(prop, lit);
            }
        }
    }
    
    private String getPropertyName(PropertyConstraint.ConstraintType type) {
        return switch (type) {
            case MIN_COUNT -> "minCount";
            case MAX_COUNT -> "maxCount";
            case MIN_LENGTH -> "minLength";
            case MAX_LENGTH -> "maxLength";
            case MIN_EXCLUSIVE -> "minExclusive";
            case MAX_EXCLUSIVE -> "maxExclusive";
            case MIN_INCLUSIVE -> "minInclusive";
            case MAX_INCLUSIVE -> "maxInclusive";
            case DATATYPE -> "datatype";
            case CLASS -> "class";
            case NODE_KIND -> "nodeKind";
            case NODE -> "node";
            case PATTERN -> "pattern";
            case FLAGS -> "flags";
            case HAS_VALUE -> "hasValue";
            case IN -> "in";
            case LANGUAGE_IN -> "languageIn";
            case UNIQUE_LANG -> "uniqueLang";
            case CLOSED -> "closed";
            case EQUALS -> "equals";
            case DISJOINT -> "disjoint";
            case LESS_THAN -> "lessThan";
            case LESS_THAN_OR_EQUALS -> "lessThanOrEquals";
            case QUALIFIED_MIN_COUNT -> "qualifiedMinCount";
            case QUALIFIED_MAX_COUNT -> "qualifiedMaxCount";
            case QUALIFIED_VALUE_SHAPE -> "qualifiedValueShape";
            case QUALIFIED_VALUE_SHAPES_DISJOINT -> "qualifiedValueShapesDisjoint";
            case OR -> "or";
            case AND -> "and";
            case NOT -> "not";
            case XONE -> "xone";
            default -> type.name().toLowerCase();
        };
    }
    
    private void serializeLogicalConstraint(Resource shapeRes, LogicalConstraint lc) {
        String propName = getLogicalPropertyName(lc.getType());
        Property prop = model.createProperty(SH, propName);
        
        if (lc.getType() == LogicalConstraint.LogicalType.NOT) {
            if (lc.getOperandCount() > 0) {
                Object operand = lc.getOperands().get(0);
                RDFNode operandNode = serializeOperand(operand);
                if (operandNode != null) shapeRes.addProperty(prop, operandNode);
            }
        } else {
            List<RDFNode> nodes = new ArrayList<>();
            for (Object operand : lc.getOperands()) {
                RDFNode node = serializeOperand(operand);
                if (node != null) nodes.add(node);
            }
            if (!nodes.isEmpty()) {
                RDFList rdfList = model.createList(nodes.iterator());
                shapeRes.addProperty(prop, rdfList);
            }
        }
    }
    
    private String getLogicalPropertyName(LogicalConstraint.LogicalType type) {
        return switch (type) {
            case AND -> "and";
            case OR -> "or";
            case NOT -> "not";
            case XONE -> "xone";
        };
    }
    
    private RDFNode serializeOperand(Object operand) {
        if (operand instanceof String uri) return model.createResource(uri);
        if (operand instanceof Resource res) {
            // Deep-copy blank nodes from source model into serialization model
            if (res.isAnon() && sourceModel != null) {
                return deepCopyNode(res, sourceModel);
            }
            return res;
        }
        if (operand instanceof LogicalConstraint nested) {
            Resource nestedRes = model.createResource();
            serializeLogicalConstraint(nestedRes, nested);
            return nestedRes;
        }
        if (operand instanceof PropertyConstraint pc) {
            Resource pcRes = model.createResource();
            if (pc.getPath() != null) {
                Property pathProp = model.createProperty(SH, "path");
                pcRes.addProperty(pathProp, pc.getPath());
            }
            for (Map.Entry<PropertyConstraint.ConstraintType, Object> entry : pc.getConstraints().entrySet()) {
                serializeConstraintValue(pcRes, entry.getKey(), entry.getValue());
            }
            return pcRes;
        }
        return null;
    }
    
    private RDFList createRDFList(List<?> items) {
        List<RDFNode> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof RDFNode node) nodes.add(node);
            else if (item instanceof String str) nodes.add(model.createLiteral(str));
            else if (item instanceof Number num) nodes.add(model.createTypedLiteral(num));
        }
        return model.createList(nodes.iterator());
    }
    
    /**
     * Deep-copy an RDFNode from a source model into the serialization model.
     * For URI resources and literals, returns the node directly (model-independent).
     * For blank nodes, recursively copies all properties into a new blank node.
     */
    private RDFNode deepCopyNode(RDFNode node, Model sourceModel) {
        if (node.isURIResource()) {
            return model.createResource(node.asResource().getURI());
        }
        if (node.isLiteral()) {
            return node; // Literals are model-independent
        }
        // Blank node: create a new blank node and copy all properties
        Resource src = node.asResource();
        Resource dst = model.createResource();
        StmtIterator stmts = sourceModel.listStatements(src, null, (RDFNode) null);
        while (stmts.hasNext()) {
            Statement stmt = stmts.next();
            Property prop = stmt.getPredicate();
            RDFNode obj = stmt.getObject();
            // For rdf:first/rdf:rest (RDF list nodes), copy recursively
            dst.addProperty(prop, deepCopyNode(obj, sourceModel));
        }
        return dst;
    }
    
    /**
     * Create an RDF list in the serialization model by deep-copying items from a source model.
     * Used for sh:xone and other constraints that reference blank node shapes.
     */
    private RDFList createRDFListDeepCopy(List<?> items, Model sourceModel) {
        List<RDFNode> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof RDFNode node) {
                nodes.add(deepCopyNode(node, sourceModel));
            }
        }
        return model.createList(nodes.iterator());
    }
}
