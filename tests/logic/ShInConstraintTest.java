package logic;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import parser.ShaclParser;
import model.*;

import org.apache.jena.rdf.model.*;
import java.io.StringReader;
import java.util.*;
import java.util.Set;

/**
 * Tests for sh:in constraint handling in SHACL parsing.
 * 
 * Note: sh:in is treated as an attribute constraint (like minCount/maxCount),
 * not as a logical constraint in direct AND context.
 * However, in OR branches, sh:in values become logical symbols.
 * 
 * This matches the behavior where:
 * - Direct sh:in: stored as constraint values, merged via intersection in AND context
 * - sh:in in OR branch: each value becomes a symbol in the logical expression
 */
class ShInConstraintTest {
    
    private ShaclParser parser;
    
    @BeforeEach
    void setUp() {
        parser = new ShaclParser();
    }
    
    private NodeShape getFirstShape(ShapeGraph shapeGraph) {
        return shapeGraph.getNodeShapes().iterator().next();
    }
    
    // ==================== sh:in in OR Context (Creates Logical Expressions) ====================
    
    @Test
    @DisplayName("Test sh:in inside sh:or branches creates logical expression")
    void testShInInOrBranches() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:or (
                        [ sh:in ( ex:Active ex:Inactive ) ]
                        [ sh:in ( ex:Pending ex:Closed ) ]
                    ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression from OR");
        String exprStr = pc.getLogicalExpression().toString();
        System.out.println("sh:in in OR branches expression: " + exprStr);
        
        // Should be Or of two And expressions (each branch with its in values)
        assertTrue(exprStr.startsWith("Or("), "Should have OR structure");
        assertTrue(exprStr.contains("And("), "Each branch should have AND structure");
        assertTrue(exprStr.contains("in_"), "Should contain sh:in symbols");
    }
    
    @Test
    @DisplayName("Verify sh:in OR branch creates correct structure")
    void testShInOrBranchStructure() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:or (
                        [ sh:in ( ex:A ex:B ) ]
                        [ sh:in ( ex:C ex:D ) ]
                    ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        LogicalExpression expr = pc.getLogicalExpression();
        
        assertNotNull(expr, "Should have logical expression");
        
        // Verify structure: Or(And(in_A, in_B), And(in_C, in_D))
        assertTrue(expr instanceof OrExpr, "Top level should be Or");
        OrExpr orExpr = (OrExpr) expr;
        
        // Each branch should be an And
        for (LogicalExpression branch : orExpr.getArgs()) {
            assertTrue(branch instanceof AndExpr, "Each OR branch should be And: " + branch);
        }
        
        System.out.println("Structure verified: " + expr);
    }
    
    @Test
    @DisplayName("Test sh:in with nodeKind inside sh:or")
    void testShInWithNodeKindInOr() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:or (
                        [ sh:nodeKind sh:IRI ; sh:in ( ex:A ex:B ) ]
                        [ sh:nodeKind sh:Literal ; sh:in ( "active" "inactive" ) ]
                    ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression");
        String exprStr = pc.getLogicalExpression().toString();
        System.out.println("sh:in + nodeKind in OR expression: " + exprStr);
        
        // Should have OR with branches containing both nodeKind and in values
        assertTrue(exprStr.startsWith("Or("), "Should have OR structure");
        assertTrue(exprStr.contains("nk_"), "Should contain nodeKind symbols");
        assertTrue(exprStr.contains("in_"), "Should contain sh:in symbols");
    }
    
    // ==================== sh:in as Attribute Constraint (Direct context) ====================
    
    @Test
    @DisplayName("Test sh:in stored as constraint (not logical expression)")
    void testShInStoredAsConstraint() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ( ex:Active ex:Inactive ex:Pending ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        // sh:in is stored as a constraint, not as a logical expression
        // (no OR/AND context, so it's attribute-level like minCount)
        Object inConstraint = pc.getConstraint(PropertyConstraint.ConstraintType.IN);
        assertNotNull(inConstraint, "Should have IN constraint stored");
        
        List<?> inValues = (List<?>) inConstraint;
        assertEquals(3, inValues.size(), "Should have 3 in-values");
        
        System.out.println("sh:in constraint stored: " + inValues.size() + " values");
    }
    
    @Test
    @DisplayName("Test sh:in symbols are assigned")
    void testShInSymbolsAssigned() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ( ex:Active ex:Inactive ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        // Check that symbols were assigned for in-values
        Set<String> inSymbols = pc.getInSymbols();
        assertNotNull(inSymbols, "Should have in symbols set");
        assertEquals(2, inSymbols.size(), "Should have 2 symbols for 2 in-values");
        
        for (String sym : inSymbols) {
            assertTrue(sym.startsWith("in_"), "Symbol should start with in_: " + sym);
        }
        
        System.out.println("sh:in symbols: " + inSymbols);
    }
    
    @Test
    @DisplayName("Test sh:in combined with sh:nodeKind (direct context)")
    void testShInWithNodeKindDirect() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:nodeKind sh:IRI ;
                    sh:in ( ex:Active ex:Inactive ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        // sh:nodeKind is stored as both property-level attribute AND logical symbol (dual-track)
        assertNotNull(pc.getNodeKind(), "Should have nodeKind as property attribute");
        // Dual-track: nodeKind also appears as a symbol in the logical expression
        // The serializer's removeDualTrackRedundancy will strip it at output time
        
        // sh:in is stored as constraint
        Object inConstraint = pc.getConstraint(PropertyConstraint.ConstraintType.IN);
        assertNotNull(inConstraint, "Should have IN constraint stored");
        
        System.out.println("nodeKind stored as attribute: " + pc.getNodeKind());
        System.out.println("sh:in stored as constraint");
    }
    
    // ==================== Edge Cases ====================
    
    @Test
    @DisplayName("Test empty sh:in list")
    void testShInEmptyList() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ( ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        // Should handle empty list gracefully
        ShapeGraph shapeGraph = parser.parse(model);
        assertNotNull(shapeGraph, "Should parse without error");
    }
    
    @Test
    @DisplayName("Test sh:in single value in OR")
    void testShInSingleValueInOr() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetNode ex:Test1 ;
                sh:property [
                    sh:path ex:status ;
                    sh:or (
                        [ sh:in ( ex:Active ) ]
                        [ sh:in ( ex:Inactive ) ]
                    ) ;
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression");
        String exprStr = pc.getLogicalExpression().toString();
        System.out.println("Single value sh:in in OR: " + exprStr);
        
        // Each branch with single value should be just a symbol (not And)
        assertTrue(exprStr.contains("in_"), "Should contain sh:in symbols");
    }
}

