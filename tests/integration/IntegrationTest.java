package integration;

import parser.ShaclParser;
import reducer.ShapeReducer;
import model.*;
import logic.*;
import serializer.TurtleSerializer;
import org.junit.jupiter.api.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for full workflow: parse -> simplify -> serialize.
 */
class IntegrationTest {
    
    private ShaclParser parser;
    private ShapeReducer reducer;
    private TurtleSerializer serializer;
    
    @BeforeEach
    void setUp() {
        parser = new ShaclParser();
        reducer = new ShapeReducer();
        serializer = new TurtleSerializer();
    }
    
    @Test
    @DisplayName("Full workflow: Complex shape with nested logic")
    void testComplexWorkflow() throws Exception {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:ComplexShape a sh:NodeShape ;
                sh:targetClass ex:ComplexEntity ;
                sh:property [
                    sh:path ex:field1 ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ; sh:class ex:TypeA ]
                        [ sh:nodeKind sh:Literal ; sh:datatype xsd:string ]
                    )
                ] ;
                sh:property [
                    sh:path ex:field2 ;
                    sh:and (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:class ex:TypeB ]
                    )
                ] ;
                sh:property [
                    sh:path ex:field3 ;
                    sh:not [ sh:nodeKind sh:BlankNode ]
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        // Parse
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        assertNotNull(graph);
        assertEquals(1, graph.getNodeShapes().size());
        
        NodeShape shape = new ArrayList<>(graph.getNodeShapes()).get(0);
        assertEquals(3, shape.getPropertyConstraints().size());
        
        // Verify logical expressions were parsed
        // Look for a property with an OrExpr
        boolean hasOrExpr = shape.getPropertyConstraints().stream()
            .anyMatch(pc -> pc.getLogicalExpression() instanceof OrExpr);
        assertTrue(hasOrExpr, "Should have at least one property with sh:or");
        
        // Serialize
        Path tempOut = Files.createTempFile("integration_test_", ".ttl");
        serializer.serialize(graph, tempOut);
        
        // Verify output is valid RDF
        Model outModel = RDFDataMgr.loadModel(tempOut.toString());
        assertTrue(outModel.size() > 0);
        
        // Cleanup
        Files.deleteIfExists(tempOut);
    }
    
    @Test
    @DisplayName("Workflow: Multiple shapes with constraint merging")
    void testConstraintMergingWorkflow() throws Exception {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:Shape1 a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:name ;
                    sh:nodeKind sh:Literal ;
                    sh:minCount 1
                ] ;
                sh:property [
                    sh:path ex:name ;
                    sh:datatype xsd:string ;
                    sh:maxCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        // Parse (merging is now always enabled)
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        
        NodeShape shape = new ArrayList<>(graph.getNodeShapes()).get(0);
        // Should have merged the two properties with same path
        assertTrue(shape.getPropertyConstraints().size() <= 2);
        
        // Verify the merged constraint has both nodeKind and datatype
        PropertyConstraint merged = shape.getPropertyConstraints().stream()
            .filter(pc -> pc.getPath().toString().contains("name"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(merged);
        // sh:nodeKind and sh:datatype are now property-level attributes, not logical expression symbols
        assertNotNull(merged.getNodeKind(), "Merged constraint should have nodeKind attribute");
        assertNotNull(merged.getDatatype(), "Merged constraint should have datatype attribute");
    }
    
    @Test
    @DisplayName("Workflow: Cache effectiveness check")
    void testCacheEffectiveness() throws Exception {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:Shape1 a sh:NodeShape ;
                sh:targetClass ex:Type1 ;
                sh:property [
                    sh:path ex:prop1 ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:nodeKind sh:Literal ]
                    )
                ] .
            
            ex:Shape2 a sh:NodeShape ;
                sh:targetClass ex:Type2 ;
                sh:property [
                    sh:path ex:prop2 ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:nodeKind sh:Literal ]
                    )
                ] .
            
            ex:Shape3 a sh:NodeShape ;
                sh:targetClass ex:Type3 ;
                sh:property [
                    sh:path ex:prop3 ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:nodeKind sh:Literal ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        // Parse with cache enabled
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        assertNotNull(graph);
        
        // Check cache stats
        LogicSimplifier.CacheStats stats = reducer.getSimplifier().getCacheStats();
        assertNotNull(stats);
        
        // With 3 identical Or expressions, we should see cache hits
        System.out.println("Cache stats: " + stats);
        assertTrue(stats.hits() > 0, "Should have cache hits for repeated expressions");
        assertTrue(stats.hitRate() > 0.0, "Cache hit rate should be > 0%");
    }
    
    @Test
    @DisplayName("Workflow: Symbol table consistency")
    void testSymbolTableConsistency() throws Exception {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:Shape a sh:NodeShape ;
                sh:targetClass ex:Entity ;
                sh:property [
                    sh:path ex:field1 ;
                    sh:nodeKind sh:IRI ;
                    sh:class ex:TypeA
                ] ;
                sh:property [
                    sh:path ex:field2 ;
                    sh:nodeKind sh:IRI ;
                    sh:class ex:TypeB
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        SymbolTable symbolTable = graph.getSymbolTable();
        
        assertNotNull(symbolTable);
        assertTrue(symbolTable.getAllSymbols().size() > 0);
        
        // Verify all symbols have unique names
        Set<String> uniqueSymbols = new HashSet<>(symbolTable.getAllSymbols());
        assertEquals(uniqueSymbols.size(), symbolTable.getAllSymbols().size(),
            "All symbol names should be unique");
        
        // Verify symbol reverse lookup works
        for (String symbol : symbolTable.getAllSymbols()) {
            SymbolTable.ConstraintInfo info = symbolTable.getConstraintInfo(symbol);
            assertNotNull(info, "Symbol " + symbol + " should have constraint info");
        }
    }
    
    @Test
    @DisplayName("Workflow: Empty shape graph")
    void testEmptyShapeGraph() throws Exception {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        assertNotNull(graph);
        assertEquals(0, graph.getNodeShapes().size());
        
        // Should still be able to serialize
        Path tempOut = Files.createTempFile("empty_test_", ".ttl");
        serializer.serialize(graph, tempOut);
        
        Model outModel = RDFDataMgr.loadModel(tempOut.toString());
        assertNotNull(outModel);
        
        Files.deleteIfExists(tempOut);
    }
    
    @Test
    @DisplayName("Workflow: Performance baseline for small input")
    void testPerformanceBaseline() throws Exception {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:Shape a sh:NodeShape ;
                sh:targetClass ex:Entity ;
                sh:property [
                    sh:path ex:field ;
                    sh:nodeKind sh:IRI
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        // Warmup
        for (int i = 0; i < 3; i++) {
            ShapeGraph warmupGraph = parser.parse(model);
            new ShapeReducer().reduce(warmupGraph);
        }
        
        // Measure
        long start = System.nanoTime();
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        long duration = (System.nanoTime() - start) / 1_000_000; // ms
        
        assertNotNull(graph);
        System.out.println("Small input parsing time: " + duration + "ms");
        
        // Should be very fast for small input
        assertTrue(duration < 100, "Small input should parse in < 100ms, took " + duration + "ms");
    }
    
    @Test
    @DisplayName("Dual-track: nodeKind deduplication across top-level and OR branches")
    void testDualTrackNodeKindDedup() throws Exception {
        // Scenario: sh:nodeKind sh:IRI appears both at the top level (via AND merge)
        // and inside each OR branch. After boolean simplification the common factor
        // is extracted, but the serializer must NOT emit sh:nodeKind twice.
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Entity ;
                sh:property [
                    sh:path ex:field ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ; sh:class ex:TypeA ]
                        [ sh:nodeKind sh:IRI ; sh:class ex:TypeB ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shacl), null, "TURTLE");
        
        ShapeGraph graph = parser.parse(model);
        reducer.reduce(graph);
        
        // Serialize to temp file
        Path tempOut = Files.createTempFile("dualtrack_test_", ".ttl");
        serializer.serialize(graph, tempOut);
        
        // Read serialized output as RDF
        Model outModel = RDFDataMgr.loadModel(tempOut.toString());
        
        // Count sh:nodeKind triples — should be exactly 1, not 2
        int nodeKindCount = 0;
        var it = outModel.listStatements(null,
                ResourceFactory.createProperty("http://www.w3.org/ns/shacl#nodeKind"),
                (org.apache.jena.rdf.model.RDFNode) null);
        while (it.hasNext()) { it.next(); nodeKindCount++; }
        
        assertEquals(1, nodeKindCount,
                "Output should have exactly 1 sh:nodeKind triple (dual-track dedup), found " + nodeKindCount);
        
        // Verify sh:or is still present (the class constraints differ)
        int orCount = 0;
        var orIt = outModel.listStatements(null,
                ResourceFactory.createProperty("http://www.w3.org/ns/shacl#or"),
                (org.apache.jena.rdf.model.RDFNode) null);
        while (orIt.hasNext()) { orIt.next(); orCount++; }
        assertTrue(orCount >= 1, "Output should still have sh:or for the differing sh:class branches");
        
        Files.deleteIfExists(tempOut);
    }
}
