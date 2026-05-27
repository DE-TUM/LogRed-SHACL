import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import parser.ShaclParser;
import reducer.ShapeReducer;
import model.*;
import logic.*;
import serializer.TurtleSerializer;
import org.apache.jena.riot.RDFFormat;

import org.apache.jena.rdf.model.*;

import java.io.*;
import java.nio.file.*;

/**
 * Tests for xone intra-branch simplification (Algorithm 11 Step a).
 * 
 * Verifies that each sh:xone branch is individually simplified
 * (parse → optimise → boolean-simplify → rebuild) before structural
 * deduplication removes duplicate branches.
 */
public class XoneBranchSimplificationTest {

    private ShaclParser parser;
    private ShapeReducer reducer;

    @BeforeEach
    void setUp() {
        parser = new ShaclParser();
        reducer = new ShapeReducer();
    }

    // ==================== Intra-branch simplification ====================

    @Test
    @DisplayName("Xone branch with redundant sh:or should be simplified (absorption)")
    void testXoneBranchOrAbsorption() {
        // Branch 1 has sh:or with absorption: (A) | (A & B) → A
        // The inner sh:or should be simplified to just sh:class ex:Animal
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:type ;
                    sh:xone (
                        [ sh:or (
                            [ sh:class ex:Animal ]
                            [ sh:class ex:Animal ; sh:nodeKind sh:IRI ]
                          )
                        ]
                        [ sh:class ex:Plant ]
                    )
                ] .
            """;

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");

        ShapeGraph sg = parser.parse(model);
        reducer.reduce(sg);

        String output = serializeToString(sg);
        System.out.println("=== Absorption test output ===\n" + output);

        // After absorption, the first branch should no longer have sh:or —
        // it should be simplified to just sh:class ex:Animal
        assertTrue(output.contains("sh:xone"), "Should still have sh:xone");
        // The OR was absorbed, so "sh:or" should NOT appear in the output
        assertFalse(output.contains("sh:or"),
                "sh:or should be absorbed away (A | (A & B) → A)");
        // Both sh:class values should still be present
        assertTrue(output.contains("Animal"), "First branch should reference Animal");
        assertTrue(output.contains("Plant"), "Second branch should reference Plant");
    }

    @Test
    @DisplayName("Xone branches that become identical after simplification → dedup")
    void testXoneBranchDedupAfterSimplification() {
        // Two branches that look different but simplify to the same thing:
        // Branch 1: sh:or ( [sh:class A] [sh:class A ; sh:nodeKind sh:IRI] ) → sh:class A
        // Branch 2: sh:class A (already simple)
        // After simplification both are structurally equivalent → dedup to xone(A, A) 
        // (preserving one duplicate pair for contradiction semantics)
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:type ;
                    sh:xone (
                        [ sh:or (
                            [ sh:class ex:Animal ]
                            [ sh:class ex:Animal ; sh:nodeKind sh:IRI ]
                          )
                        ]
                        [ sh:class ex:Animal ]
                    )
                ] .
            """;

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");

        ShapeGraph sg = parser.parse(model);
        reducer.reduce(sg);

        String output = serializeToString(sg);
        System.out.println("=== Dedup-after-simplify test output ===\n" + output);

        assertTrue(output.contains("sh:xone"), "Should still have sh:xone");
        // sh:or should have been absorbed away
        assertFalse(output.contains("sh:or"),
                "sh:or should be absorbed away inside the first branch");
    }

    @Test
    @DisplayName("Xone branch with trivial minCount 0 should be optimised away")
    void testXoneBranchTrivialMinCount() {
        // Branch 1 has minCount 0 (trivial, should be removed by optimizer)
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:value ;
                    sh:xone (
                        [ sh:minCount 0 ; sh:class ex:TypeA ]
                        [ sh:class ex:TypeB ]
                    )
                ] .
            """;

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");

        ShapeGraph sg = parser.parse(model);
        reducer.reduce(sg);

        String output = serializeToString(sg);
        System.out.println("=== Trivial minCount test output ===\n" + output);

        assertTrue(output.contains("sh:xone"), "Should still have sh:xone");
        // minCount 0 is trivially satisfied → should be removed
        assertFalse(output.contains("minCount"),
                "minCount 0 should be optimised away from xone branch");
    }

    @Test
    @DisplayName("Nested xone within xone branch should be preserved")
    void testNestedXonePreserved() {
        // Branch 1 itself contains an sh:xone — it should be left opaque
        // but any simplifiable constraints alongside it should still be simplified
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:data ;
                    sh:xone (
                        [ sh:minCount 0 ;
                          sh:xone (
                            [ sh:class ex:X ]
                            [ sh:class ex:Y ]
                          )
                        ]
                        [ sh:class ex:Z ]
                    )
                ] .
            """;

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");

        ShapeGraph sg = parser.parse(model);
        reducer.reduce(sg);

        String output = serializeToString(sg);
        System.out.println("=== Nested xone test output ===\n" + output);

        // The outer xone should still be present
        assertTrue(output.contains("sh:xone"), "Should have sh:xone");
        // minCount 0 should be removed from the first branch
        assertFalse(output.contains("minCount"),
                "minCount 0 should be optimised away even in nested xone branches");
    }

    @Test
    @DisplayName("Simple xone branches with no redundancy pass through unchanged")
    void testSimpleXoneBranchesUnchanged() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:contact ;
                    sh:xone (
                        [ sh:class ex:Email ; sh:minCount 1 ]
                        [ sh:class ex:Phone ; sh:minCount 1 ]
                    )
                ] .
            """;

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");

        ShapeGraph sg = parser.parse(model);
        reducer.reduce(sg);

        String output = serializeToString(sg);
        System.out.println("=== Simple xone test output ===\n" + output);

        assertTrue(output.contains("sh:xone"), "Should have sh:xone");
        assertTrue(output.contains("Email"), "First branch should have Email");
        assertTrue(output.contains("Phone"), "Second branch should have Phone");
        assertTrue(output.contains("minCount"), "minCount should be preserved (non-trivial)");
    }

    @Test
    @DisplayName("NodeShape-level xone branches should also be simplified")
    void testNodeLevelXoneBranchSimplification() {
        // NodeShape-level xone with branches that have internal redundancy
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:MainShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:xone (
                    ex:BranchA
                    ex:BranchB
                ) .
            
            ex:BranchA a sh:NodeShape ;
                sh:property [
                    sh:path ex:val ;
                    sh:minCount 0 ;
                    sh:class ex:TypeA
                ] .
            
            ex:BranchB a sh:NodeShape ;
                sh:property [
                    sh:path ex:val ;
                    sh:class ex:TypeB
                ] .
            """;

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");

        ShapeGraph sg = parser.parse(model);
        reducer.reduce(sg);

        String output = serializeToString(sg);
        System.out.println("=== NodeShape xone test output ===\n" + output);

        assertTrue(output.contains("sh:xone"), "Should have node-level sh:xone");
    }

    // ==================== Helper ====================

    private String serializeToString(ShapeGraph shapeGraph) {
        try {
            TurtleSerializer serializer = new TurtleSerializer()
                    .setOutputFormat(RDFFormat.TURTLE_PRETTY);
            Path tempFile = Files.createTempFile("xone_test_output", ".ttl");
            serializer.serialize(shapeGraph, tempFile);
            String content = Files.readString(tempFile);
            Files.delete(tempFile);
            return content;
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }
}
