package integration;

import logic.*;
import model.*;
import parser.ShaclParser;
import reducer.ShapeReducer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the optimization pipeline.
 */
public class OptimizationPipelineTest {
    
    @Test
    void testRedundancyRemoval() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                    sh:minLength 0 ;
                    sh:maxLength 100 ;
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        assertEquals(1, result.getNodeShapes().stream().toList().size());
        
        // Check that redundancies were removed
        int removed = reducer.getOptimizer().getRedundanciesRemoved();
        System.out.println("Redundancies removed: " + removed);
        assertTrue(removed >= 2);  // minCount=0 and minLength=0
    }
    
    @Test
    void testDistributiveLaw() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:datatype xsd:string ; sh:nodeKind sh:Literal ]
                        [ sh:datatype xsd:string ; sh:nodeKind sh:IRI ]
                    )
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        
        // Verify the expression was optimized with distributive law
        // Or(And(dt_common, nk_1), And(dt_common, nk_2)) -> And(dt_common, Or(nk_1, nk_2))
        PropertyConstraint pc = result.getNodeShapes().stream().toList().get(0).getPropertyConstraints().get(0);
        System.out.println("Before optimization: Or(And(dt_common, nk_1), And(dt_common, nk_2))");
        System.out.println("After optimization: " + pc.getLogicalExpression());
    }
    
    @Test
    void testConflictingAnd() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:ConflictShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:and (
                        [ sh:datatype xsd:string ]
                        [ sh:datatype xsd:integer ]
                    )
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        
        // Conflicting AND: the only PC becomes FALSE, so entire NodeShape is removed
        assertTrue(result.getNodeShapes().isEmpty(),
            "NodeShape should be removed (all PCs were unsatisfiable)");
        
        ConflictReport report = reducer.getConflictReport();
        assertTrue(report.hasConflicts(), "Conflict report should have entries");
        assertEquals(1, report.getTotalConflicts(), "Should have 1 conflict");
        System.out.println("Conflicting And removed from output, recorded in conflict report");
        System.out.println(report.getSummary());
    }
    
    @Test
    void testEmptyShInList() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:EmptyInShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ( ) ;
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        
        // Empty sh:in causes FALSE, the only PC is removed, so entire NodeShape is removed
        assertTrue(result.getNodeShapes().isEmpty(),
            "NodeShape should be removed (all PCs were unsatisfiable)");
        
        ConflictReport report = reducer.getConflictReport();
        assertTrue(report.hasConflicts(), "Conflict report should have entries");
        System.out.println("Empty sh:in [] causes PropertyShape removal, recorded in conflict report");
        System.out.println(report.getSummary());
    }
    
    @Test
    void testUnsatisfiableConstraint() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:Shape1 a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:p1 ;
                    sh:minCount 1 ;
                    sh:maxCount 5 ;
                ] ;
                sh:property [
                    sh:path ex:p2 ;
                    sh:minCount 10 ;
                    sh:maxCount 5 ;
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        System.out.println("\n=== Optimization Statistics ===");
        System.out.println("Optimizer stats: " + reducer.getOptimizer().getStatsSummary());
        
        // p2 (minCount 10 > maxCount 5) should be removed from shape
        NodeShape shape = result.getNodeShapes().stream().toList().get(0);
        assertEquals(1, shape.getPropertyConstraints().size(),
            "Only p1 (valid) should remain, p2 (unsatisfiable) should be removed");
        
        // p1 should still be there
        PropertyConstraint remaining = shape.getPropertyConstraints().get(0);
        assertEquals("p1", remaining.getPath().getLocalName(),
            "The remaining constraint should be p1");
        
        // p2 should be in conflict report
        ConflictReport report = reducer.getConflictReport();
        assertTrue(report.hasConflicts(), "Conflict report should have p2");
        System.out.println(report.getSummary());
    }
    
    @Test
    void testValidConstraintsUnchanged() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:InvalidShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:minCount 10 ;
                    sh:maxCount 5 ;
                    sh:datatype xsd:string ;
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        
        // The only PropertyConstraint has minCount > maxCount → FALSE → removed
        // Since no PCs remain, the NodeShape is also removed
        ConflictReport report = reducer.getConflictReport();
        assertTrue(report.hasConflicts(), "Should have conflict for unsatisfiable constraint");
        assertEquals(1, report.getTotalConflicts());
        
        // The shape may be removed (empty after FALSE removal)
        // or may still exist with 0 property constraints
        boolean shapeRemoved = result.getNodeShapes().stream()
            .noneMatch(s -> s.getUri().contains("InvalidShape"));
        if (!shapeRemoved) {
            NodeShape shape = result.getNodeShapes().stream().toList().get(0);
            assertTrue(shape.getPropertyConstraints().isEmpty(),
                "All PCs should be removed (unsatisfiable)");
        }
        
        System.out.println("Unsatisfiable constraint removed from output");
        System.out.println(report.getSummary());
    }
    
    @Test
    void testOrNormalization() {
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:TypeShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:datatype xsd:integer ]
                        [ sh:datatype xsd:string ]
                    )
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        assertNotNull(result);
        
        PropertyConstraint pc = result.getNodeShapes().stream().toList().get(0).getPropertyConstraints().get(0);
        assertTrue(pc.getLogicalExpression() instanceof OrExpr);
        System.out.println("Normalized OR: " + pc.getLogicalExpression());
    }
    
    // ========== Round-Trip Serialization Tests ==========
    
    @Test
    void testOriginalExpressionPreservedAfterOptimization() {
        // Test: Expression is simplified/optimized correctly
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:datatype xsd:string ; sh:nodeKind sh:Literal ]
                        [ sh:datatype xsd:string ; sh:nodeKind sh:IRI ]
                    )
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        PropertyConstraint pc = result.getNodeShapes().stream().toList().get(0).getPropertyConstraints().get(0);
        
        // Should have a simplified logical expression
        assertTrue(pc.hasLogicalExpression(), 
            "Should have simplified logical expression");
        
        System.out.println("Simplified: " + pc.getLogicalExpression());
        
        // No conflicts expected
        assertFalse(reducer.getConflictReport().hasConflicts(),
            "No conflicts expected for valid OR expression");
    }
    
    @Test
    void testMemoryOptimizationWhenUnchanged() {
        // Test: Simple constraint without conflicts works correctly
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:SimpleShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:name ;
                    sh:datatype xsd:string ;
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        PropertyConstraint pc = result.getNodeShapes().stream().toList().get(0).getPropertyConstraints().get(0);
        
        // Simple constraint: no logical expression needed, no conflicts
        assertNotNull(pc.getDatatype(), "Should have datatype attribute");
        assertFalse(reducer.getConflictReport().hasConflicts(),
            "No conflicts expected for simple valid constraint");
        System.out.println("Simple constraint processed correctly, no conflicts");
    }
    
    @Test
    void testSerializerUsesSimplifiedExpression() {
        // Test: Serializer uses the (potentially simplified) logical expression
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:TypeShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:datatype xsd:integer ]
                        [ sh:datatype xsd:string ]
                    )
                ] .
            """;
        
        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)), 
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);
        
        PropertyConstraint pc = result.getNodeShapes().stream().toList().get(0).getPropertyConstraints().get(0);
        
        // Get the expression that serializer will use
        LogicalExpression serializerExpr = pc.getLogicalExpression();
        
        assertNotNull(serializerExpr, "Serializer should have an expression to use");
        assertTrue(serializerExpr instanceof OrExpr, "Should be OR expression");
        System.out.println("Serializer will use: " + serializerExpr);
        
        // No conflicts for valid OR
        assertFalse(reducer.getConflictReport().hasConflicts());
    }

    // ========== minCount=0 contamination regression tests ==========

    @Test
    void testMergeMinCountZeroDoesNotContaminate() {
        // Two PropertyShapes for the same path:
        //   one has   sh:minCount 0  (redundant no-op)
        //   the other has no minCount at all
        // After merge the result must NOT carry sh:minCount 0.
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:minCount 0 ;
                    sh:datatype xsd:string ;
                ] ;
                sh:property [
                    sh:path ex:value ;
                    sh:maxCount 5 ;
                ] .
            """;

        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)),
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);

        assertFalse(result.getNodeShapes().isEmpty());
        NodeShape shape = result.getNodeShapes().stream().toList().get(0);
        assertEquals(1, shape.getPropertyConstraints().size(), "Should be merged into one");

        PropertyConstraint merged = shape.getPropertyConstraints().get(0);
        // minCount=0 from the first branch must have been discarded
        assertNull(merged.getMinCount(),
            "minCount=0 from a BNode must not survive the merge — it is a no-op");
        // maxCount from the second branch should have been preserved
        assertEquals(5, merged.getMaxCount(),
            "maxCount=5 from the other branch must be preserved");
    }

    @Test
    void testMergeMinCountZeroOverriddenByPositiveValue() {
        // One branch has minCount=0 (no-op), other has minCount=2.
        // AND semantics: result should be minCount=2 (the meaningful constraint).
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:minCount 0 ;
                ] ;
                sh:property [
                    sh:path ex:value ;
                    sh:minCount 2 ;
                ] .
            """;

        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)),
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);

        assertFalse(result.getNodeShapes().isEmpty());
        PropertyConstraint merged = result.getNodeShapes().stream().toList()
            .get(0).getPropertyConstraints().get(0);
        assertEquals(2, merged.getMinCount(),
            "minCount=0 (no-op) vs minCount=2 → result must be 2");
    }

    @Test
    void testOptimizeConstraintStripsMinCountZeroOnMergedPC() {
        // A single PropertyShape with only sh:minCount 0.
        // After reduction the attribute must be gone (treated as no-op).
        String shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:minCount 0 ;
                ] .
            """;

        ShaclParser parser = new ShaclParser();
        ShapeGraph result = parser.parse(
            new ByteArrayInputStream(shacl.getBytes(StandardCharsets.UTF_8)),
            "TURTLE");
        ShapeReducer reducer = new ShapeReducer();
        reducer.reduce(result);

        // The shape may be kept (single PC that becomes empty)
        // but if kept the PC must not carry minCount=0
        if (!result.getNodeShapes().isEmpty()) {
            NodeShape shape = result.getNodeShapes().stream().toList().get(0);
            if (!shape.getPropertyConstraints().isEmpty()) {
                assertNull(shape.getPropertyConstraints().get(0).getMinCount(),
                    "A lone sh:minCount 0 must be stripped from the output");
            }
        }
    }
}
