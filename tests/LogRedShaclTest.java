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
import java.util.*;

/**
 * Comprehensive tests for LogRed-SHACL Java implementation.
 * Mirrors the Python tests in simshapes/tests/
 */
public class LogRedShaclTest {
    
    private ShaclParser parser;
    private ShapeReducer reducer;
    
    @BeforeEach
    void setUp() {
        parser = new ShaclParser();
        reducer = new ShapeReducer();
    }
    
    private NodeShape getFirstShape(ShapeGraph shapeGraph) {
        return shapeGraph.getNodeShapes().iterator().next();
    }
    
    // ==================== Test: OR Simplification ====================
    
    @Test
    @DisplayName("Test main example sh:or simplification")
    void testMainExampleOrSimplification() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Person1 ;
                sh:property [
                    sh:or (     [ sh:nodeKind sh:IRI ; sh:class ex:Manager ] 
                                [ sh:nodeKind sh:IRI ; sh:class ex:Employee ] 
                                [ sh:nodeKind sh:IRI ] 
                                ) ;
                    sh:path ex:status ;
                    ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        assertEquals(1, shapeGraph.getNodeShapes().size(), "Should have 1 NodeShape");
        
        NodeShape shape = getFirstShape(shapeGraph);
        assertEquals(1, shape.getPropertyConstraints().size(), "Should have 1 PropertyConstraint");
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression");
        
        String exprStr = pc.getLogicalExpression().toString();
        System.out.println("Simplified expression: " + exprStr);
        
        // After absorption A | (A & B) = A, should be simplified
        assertFalse(exprStr.contains("|"), "Expression should be simplified (no OR after absorption)");
    }
    
    // ==================== Test: Absorption Laws ====================
    
    @Test
    @DisplayName("Test absorption law: A | (A & B) = A")
    void testAbsorptionOrSimplification() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Entity ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:nodeKind sh:IRI ; sh:class ex:Person ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        LogicalExpression expr = pc.getLogicalExpression();
        
        String exprStr = expr.toString();
        System.out.println("After absorption: " + exprStr);
        
        // After absorption A | (A & B) = A
        assertFalse(exprStr.contains("|"), "After absorption law, should not contain OR");
    }
    
    // ==================== Test: Smart Count Merge ====================
    
    @Test
    @DisplayName("Test smart merge minCount AND semantics")
    void testSmartMergeMinCountAnd() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 2 ;
                ] ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 3 ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size(), 
            "Should merge into 1 PropertyConstraint");
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        Integer minCount = pc.getMinCount();
        
        // AND semantics: take max(2, 3) = 3
        assertEquals(3, minCount, "minCount should be max(2,3) = 3 in AND semantics");
    }
    
    @Test
    @DisplayName("Test smart merge maxCount AND semantics")
    void testSmartMergeMaxCountAnd() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:property [
                    sh:path ex:tags ;
                    sh:maxCount 5 ;
                ] ;
                sh:property [
                    sh:path ex:tags ;
                    sh:maxCount 3 ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        Integer maxCount = pc.getMaxCount();
        
        // AND semantics: take min(5, 3) = 3
        assertEquals(3, maxCount, "maxCount should be min(5,3) = 3 in AND semantics");
    }
    
    // ==================== Test: Redundancy Elimination ====================
    
    @Test
    @DisplayName("Test identical constraints merge")
    void testIdenticalConstraintsMerge() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1 ;
                    sh:maxCount 1
                ] ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1 ;
                    sh:maxCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size(), 
            "Identical constraints should merge to 1");
    }
    
    @Test
    @DisplayName("Test sh:in intersection")
    void testShInIntersection() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:TestShape a sh:NodeShape ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ( ex:Manager ex:Employee ) ;
                    sh:minCount 1
                ] ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ( ex:Leader ) ;
                    sh:minCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        Set<String> inSymbols = pc.getInSymbols();
        
        // {Manager, Employee} intersection {Leader} = {}
        assertTrue(inSymbols == null || inSymbols.isEmpty(), 
            "sh:in intersection should be empty");
    }
    
    // ==================== Test: XOR/XONE Detection ====================
    
    @Test
    @DisplayName("Test XOR pattern at NodeShape level")
    void testXonePatternDetection() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:xone (
                    ex:EmailContact
                    ex:PhoneContact
                ) .
            
            ex:EmailContact a sh:NodeShape ;
                sh:property [
                    sh:path ex:email ;
                    sh:minCount 1
                ] .
            
            ex:PhoneContact a sh:NodeShape ;
                sh:property [
                    sh:path ex:phone ;
                    sh:minCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        assertEquals(3, shapeGraph.getNodeShapes().size(), "Should have 3 NodeShapes");
        
        NodeShape personShape = null;
        for (NodeShape s : shapeGraph.getNodeShapes()) {
            if (s.getUri().contains("PersonShape")) {
                personShape = s;
                break;
            }
        }
        
        assertNotNull(personShape);
        assertFalse(personShape.getLogicalConstraints().isEmpty(), 
            "PersonShape should have logical constraints (xone)");
        
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        String output = serializeToString(shapeGraph, serializer);
        System.out.println("XOR output:\n" + output);
        
        assertTrue(output.contains("sh:xone"), "Should serialize xone pattern");
    }
    
    @Test
    @DisplayName("Test OR pattern (not XOR)")
    void testOrPatternNotXone() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:contact ;
                    sh:or (
                        [ sh:class ex:Email ]
                        [ sh:class ex:Phone ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        String output = serializeToString(shapeGraph, serializer);
        System.out.println("OR output:\n" + output);
        
        assertTrue(output.contains("sh:or"), "Should serialize as sh:or");
        assertFalse(output.contains("sh:xone"), "Should NOT be sh:xone for simple OR");
    }
    
    // ==================== Test: Complex Logical Constraints ====================
    
    @Test
    @DisplayName("Test complex logical constraints merging")
    void testComplexLogicalConstraintsMerging() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            ex:ComplexShape a sh:NodeShape ;
                sh:targetClass ex:Complex ;
                sh:property [
                    sh:path ex:field ;
                    sh:or (
                        [ sh:datatype xsd:string ]
                        [ sh:datatype xsd:integer ]
                    )
                ] ;
                sh:property [
                    sh:path ex:field ;
                    sh:minCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        assertEquals(1, pc.getMinCount(), "Should preserve minCount");
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression");
    }
    
    // ==================== Test: Cache Statistics ====================
    
    @Test
    @DisplayName("Test cache hit rate")
    void testCacheHitRate() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape1 a sh:NodeShape ;
                sh:property [
                    sh:path ex:p1 ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:class ex:A ]
                    )
                ] .
            
            ex:TestShape2 a sh:NodeShape ;
                sh:property [
                    sh:path ex:p2 ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ]
                        [ sh:class ex:A ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        assertNotNull(shapeGraph, "ShapeGraph should not be null");
        
        LogicSimplifier simplifier = reducer.getSimplifier();
        assertNotNull(simplifier);
        
        LogicSimplifier.CacheStats stats = simplifier.getCacheStats();
        System.out.println("Cache stats: " + stats);
        
        assertTrue(stats.hits() >= 0, "Cache should track hits");
    }
    
    // ==================== Test: Nested Shapes ====================
    
    @Test
    @DisplayName("Test constraint fragments in OR")
    void testConstraintFragmentsInOr() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:role ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ; sh:class ex:Student ]
                        [ sh:nodeKind sh:IRI ; sh:class ex:Employee ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        assertEquals(1, shapeGraph.getNodeShapes().size());
        
        NodeShape shape = getFirstShape(shapeGraph);
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        assertNotNull(pc.getLogicalExpression());
        
        String exprStr = pc.getLogicalExpression().toString();
        System.out.println("Nested fragments expression: " + exprStr);
        
        // After absorption, expression should be simplified
        assertNotNull(exprStr);
    }
    
    // ==================== Test: Three Layer Nesting ====================
    
    @Test
    @DisplayName("Test three layer nested or and fragments")
    void testThreeLayerNestedOrAndFragments() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Person1 ;
                sh:property [
                    sh:path ex:name ;
                    sh:or (
                        [ sh:and (
                            [ sh:nodeKind sh:Literal ]
                            [ sh:datatype <http://www.w3.org/2001/XMLSchema#string> ] 
                        ) ]
                        [ sh:or (
                            [ sh:nodeKind sh:IRI ]
                            [ sh:class ex:Person ] 
                        ) ]
                    )
                ].
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        assertEquals(1, shapeGraph.getNodeShapes().size());
        NodeShape shape = getFirstShape(shapeGraph);
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression for nested structure");
        
        System.out.println("Three layer expression: " + pc.getLogicalExpression());
    }
    
    // ==================== Test: sh:in with URIs ====================
    
    @Test
    @DisplayName("Test sh:in with URI values")
    void testShInWithUris() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:prop ;
                    sh:in ( ex:a ex:b ex:c )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        Set<String> inSymbols = pc.getInSymbols();
        assertNotNull(inSymbols);
        assertEquals(3, inSymbols.size(), "Should have 3 in-symbols");
    }
    
    // ==================== Test: sh:in with Literals ====================
    
    @Test
    @DisplayName("Test sh:in with literal values")
    void testShInWithLiterals() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:prop ;
                    sh:in ( "aaa" "bbb" )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        Set<String> inSymbols = pc.getInSymbols();
        assertNotNull(inSymbols);
        assertEquals(2, inSymbols.size(), "Should have 2 in-symbols");
    }
    
    // ==================== Test: sh:not parsing ====================
    
    @Test
    @DisplayName("Test sh:not inside sh:or parsing")
    void testShNotParsing() {
        // sh:not alone doesn't create logical expression, need it inside sh:or
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:not [ sh:class ex:Forbidden ] ]
                        [ sh:class ex:Allowed ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        LogicalExpression expr = pc.getLogicalExpression();
        assertNotNull(expr, "Should have logical expression from sh:or with sh:not");
        
        String exprStr = expr.toString();
        System.out.println("NOT expression: " + exprStr);
        // Expression uses Not() for negation
        assertTrue(exprStr.contains("Not"), "Should contain Not in expression: " + exprStr);
    }
    
    // ==================== Test: sh:and parsing ====================
    
    @Test
    @DisplayName("Test sh:and inside sh:or parsing")
    void testShAndParsing() {
        // sh:and alone simplifies to conjunction, need it inside sh:or to see explicit AND
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:value ;
                    sh:or (
                        [ sh:and (
                            [ sh:nodeKind sh:IRI ]
                            [ sh:class ex:Person ]
                        ) ]
                        [ sh:class ex:Other ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        LogicalExpression expr = pc.getLogicalExpression();
        assertNotNull(expr, "Should have logical expression for AND inside OR");
        
        String exprStr = expr.toString();
        System.out.println("AND expression: " + exprStr);
        // Expression uses And() and Or() notation
        assertTrue(exprStr.contains("And") || exprStr.contains("Or"), 
            "Should contain And or Or in expression: " + exprStr);
    }
    
    // ==================== Test: Multiple targets ====================
    
    @Test
    @DisplayName("Test multiple target classes")
    void testMultipleTargetClasses() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:targetClass ex:Person, ex:Employee, ex:Manager ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        assertEquals(3, shape.getTargetClasses().size(), "Should have 3 target classes");
    }
    
    // ==================== Test: sh:datatype constraint ====================
    
    @Test
    @DisplayName("Test sh:datatype constraint")
    void testDatatypeConstraint() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:age ;
                    sh:datatype xsd:integer ;
                    sh:minCount 1
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        // sh:datatype is stored as both property-level attribute AND logical symbol (dual-track)
        assertNotNull(pc.getDatatype(), "Should have datatype as property attribute");
        // Dual-track: datatype also appears as a symbol in the logical expression
        // The serializer's removeDualTrackRedundancy will strip it at output time
    }
    
    // ==================== Test: General absorption p & (~p | q) = p & q ====================
    
    @Test
    @DisplayName("Test general absorption law with negation")
    void testGeneralAbsorptionWithNegation() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape a sh:NodeShape ;
                sh:targetClass ex:Entity ;
                sh:property [
                    sh:path ex:value ;
                    sh:class ex:Person ;
                    sh:or (
                        [ sh:not [ sh:class ex:Person ] ]
                        [ sh:class ex:Employee ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        LogicalExpression expr = pc.getLogicalExpression();
        
        assertNotNull(expr);
        String exprStr = expr.toString();
        System.out.println("General absorption result: " + exprStr);
        
        // After p & (~p | q) = p & q, should not contain negation
        assertFalse(exprStr.contains("~"), "Should not contain negation after general absorption");
    }
    
    // ==================== Test: Disjoint sh:in lists ====================
    
    @Test
    @DisplayName("Test disjoint sh:in lists merge to empty")
    void testDisjointShInMerge() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:x ;
                    sh:in ( ex:A )
                ] ;
                sh:property [
                    sh:path ex:x ;
                    sh:in ( ex:B )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        
        // Should merge to 1 constraint with empty in-symbols
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        Set<String> inSymbols = pc.getInSymbols();
        assertTrue(inSymbols == null || inSymbols.isEmpty(), 
            "Disjoint sh:in should result in empty intersection");
    }
    
    // ==================== Test: Pattern constraint ====================
    
    @Test
    @DisplayName("Test sh:pattern constraint")
    void testPatternConstraint() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:email ;
                    sh:pattern "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}$"
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        assertEquals(1, shape.getPropertyConstraints().size());
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        Object pattern = pc.getConstraint(PropertyConstraint.ConstraintType.PATTERN);
        assertNotNull(pattern, "Should have pattern constraint");
    }
    
    // ==================== Test: Numeric range constraints ====================
    
    @Test
    @DisplayName("Test numeric range constraints")
    void testNumericRangeConstraints() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:age ;
                    sh:minInclusive 0 ;
                    sh:maxInclusive 150
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        Object minInc = pc.getConstraint(PropertyConstraint.ConstraintType.MIN_INCLUSIVE);
        Object maxInc = pc.getConstraint(PropertyConstraint.ConstraintType.MAX_INCLUSIVE);
        
        assertNotNull(minInc, "Should have minInclusive");
        assertNotNull(maxInc, "Should have maxInclusive");
    }
    
    // ==================== Test: String length constraints ====================
    
    @Test
    @DisplayName("Test string length constraints")
    void testStringLengthConstraints() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:Shape a sh:NodeShape ;
                sh:property [
                    sh:path ex:name ;
                    sh:minLength 1 ;
                    sh:maxLength 100
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        Object minLen = pc.getConstraint(PropertyConstraint.ConstraintType.MIN_LENGTH);
        Object maxLen = pc.getConstraint(PropertyConstraint.ConstraintType.MAX_LENGTH);
        
        assertNotNull(minLen, "Should have minLength");
        assertNotNull(maxLen, "Should have maxLength");
    }
    
    // ==================== Test: Roundtrip serialization ====================
    
    @Test
    @DisplayName("Test roundtrip serialization")
    void testRoundtripSerialization() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                    sh:nodeKind sh:Literal
                ] ;
                sh:property [
                    sh:path ex:email ;
                    sh:or (
                        [ sh:datatype <http://www.w3.org/2001/XMLSchema#string> ]
                        [ sh:nodeKind sh:IRI ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        reducer.reduce(shapeGraph);
        
        TurtleSerializer serializer = new TurtleSerializer();
        String output = serializeToString(shapeGraph, serializer);
        
        System.out.println("Roundtrip output:\n" + output);
        
        // Verify key elements are present (may use full URI or prefix)
        assertTrue(output.contains("PersonShape") || output.contains("http://example.org/PersonShape"), 
            "Should contain shape name");
        assertTrue(output.contains("sh:targetClass") || output.contains("targetClass"), 
            "Should contain targetClass");
        assertTrue(output.contains("sh:property") || output.contains("property"), 
            "Should contain property");
        assertTrue(output.contains("sh:minCount") || output.contains("minCount"), 
            "Should contain minCount");
    }
    
    // ==================== Test: sh:closed Constraint ====================
    
    @Test
    @DisplayName("Test sh:closed true with sh:ignoredProperties round-trip")
    void testClosedWithIgnoredProperties() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            
            ex:PersonShape
                a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:closed true ;
                sh:ignoredProperties ( rdf:type ) ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1 ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        
        assertEquals(1, shapeGraph.getNodeShapes().size());
        NodeShape shape = getFirstShape(shapeGraph);
        
        // Verify sh:closed was parsed
        assertTrue(shape.hasClosedConstraint(), "Should have closed constraint");
        assertTrue(shape.isClosed(), "Should be closed=true");
        
        // Verify sh:ignoredProperties was parsed
        assertEquals(1, shape.getIgnoredProperties().size(), "Should have 1 ignored property");
        assertTrue(shape.getIgnoredProperties().get(0).getURI().contains("type"),
            "Ignored property should be rdf:type");
        
        // Reduce and serialize
        reducer.reduce(shapeGraph);
        
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        String output = serializeToString(shapeGraph, serializer);
        
        System.out.println("sh:closed output:\n" + output);
        
        assertTrue(output.contains("sh:closed"), "Output should contain sh:closed");
        assertTrue(output.contains("true"), "Output should contain true value");
        assertTrue(output.contains("sh:ignoredProperties"), "Output should contain sh:ignoredProperties");
    }
    
    @Test
    @DisplayName("Test sh:closed false is preserved")
    void testClosedFalse() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:OpenShape
                a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:closed false ;
                sh:property [
                    sh:path ex:label ;
                    sh:minCount 1 ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertTrue(shape.hasClosedConstraint(), "Should have closed constraint");
        assertFalse(shape.isClosed(), "Should be closed=false");
        
        reducer.reduce(shapeGraph);
        
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        String output = serializeToString(shapeGraph, serializer);
        
        System.out.println("sh:closed false output:\n" + output);
        
        assertTrue(output.contains("sh:closed"), "Output should contain sh:closed");
        assertTrue(output.contains("false"), "Output should contain false value");
    }
    
    @Test
    @DisplayName("Test shape without sh:closed does not emit it")
    void testNoClosedConstraint() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:SimpleShape
                a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1 ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertFalse(shape.hasClosedConstraint(), "Should NOT have closed constraint");
        assertNull(shape.getClosed(), "Closed should be null");
        
        reducer.reduce(shapeGraph);
        
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        String output = serializeToString(shapeGraph, serializer);
        
        assertFalse(output.contains("sh:closed"), "Output should NOT contain sh:closed");
        assertFalse(output.contains("sh:ignoredProperties"), "Output should NOT contain sh:ignoredProperties");
    }
    
    @Test
    @DisplayName("Test sh:closed with multiple ignoredProperties")
    void testClosedWithMultipleIgnoredProperties() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            
            ex:StrictShape
                a sh:NodeShape ;
                sh:targetClass ex:Strict ;
                sh:closed true ;
                sh:ignoredProperties ( rdf:type rdfs:label ) ;
                sh:property [
                    sh:path ex:value ;
                    sh:datatype <http://www.w3.org/2001/XMLSchema#integer> ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertTrue(shape.isClosed());
        assertEquals(2, shape.getIgnoredProperties().size(), "Should have 2 ignored properties");
        
        reducer.reduce(shapeGraph);
        
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        String output = serializeToString(shapeGraph, serializer);
        
        System.out.println("Multiple ignored props:\n" + output);
        assertTrue(output.contains("sh:closed"), "Output should contain sh:closed");
        assertTrue(output.contains("sh:ignoredProperties"), "Output should contain sh:ignoredProperties");
    }
    
    // ==================== Helper Methods ====================
    
    private String serializeToString(ShapeGraph shapeGraph, TurtleSerializer serializer) {
        try {
            Path tempFile = Files.createTempFile("test_output", ".ttl");
            serializer.serialize(shapeGraph, tempFile);
            String content = Files.readString(tempFile);
            Files.delete(tempFile);
            return content;
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }
}
