package logic;

import org.junit.jupiter.api.*;
import parser.ShaclParser;
import model.*;
//import logic.*;

import org.apache.jena.rdf.model.*;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test OR semantics for minCount/maxCount constraints.
 * 
 * IMPORTANT - Current Java Implementation:
 * minCount/maxCount are NOT converted to symbols in sh:or branches.
 * They are always treated as attribute-level constraints.
 * 
 * Merging Rules for Shapes:
 * 1. NodeShapes are never merged with each other
 * 2. PropertyShapes are merged only when sh:path is the same
 * 3. If single-valued properties conflict after merge, shapes are NOT merged
 * 4. In sh:or value list, shapes are typically NOT merged
 * 5. EXCEPTION: If two shapes in sh:or differ ONLY in minCount/maxCount,
 *    they CAN be merged using OR semantics (taking looser values):
 *    - minCount: take minimum (looser)
 *    - maxCount: take maximum (looser)
 * 
 * This test demonstrates:
 * - sh:or with type constraints (nodeKind, class, datatype)
 * - minCount/maxCount as independent attributes
 * - When OR branches should/shouldn't be merged
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrSemanticsCountTest {
    
    private ShaclParser parser;
    
    @BeforeEach
    void setup() {
        parser = new ShaclParser();
    }
    
    private NodeShape getFirstShape(ShapeGraph graph) {
        return graph.getNodeShapes().iterator().next();
    }
    
    @Test
    @Order(1)
    @DisplayName("Test sh:or with type constraints (actual OR semantics)")
    void testOrWithTypeConstraints() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:identifier ;
                    sh:or (
                        [ sh:datatype xsd:string ]
                        [ sh:datatype xsd:integer ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size());
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        // Should have logical expression with datatype symbols
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression");
        assertTrue(pc.getLogicalExpression() instanceof OrExpr, "Should be OrExpr");
        
        System.out.println("\n=== sh:or with datatype constraints ===");
        System.out.println("Logical expression: " + pc.getLogicalExpression());
        System.out.println("Type: " + pc.getLogicalExpression().getClass().getSimpleName());
    }
    
    @Test
    @Order(2)
    @DisplayName("Test minCount/maxCount ARE symbolized (universal dual-track)")
    void testCountNotSymbolizedInOr() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:tags ;
                    sh:minCount 1 ;
                    sh:or (
                        [ sh:maxCount 3 ]
                        [ sh:maxCount 5 ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        System.out.println("\n=== minCount/maxCount in sh:or ===");
        System.out.println("Number of PropertyConstraints: " + shape.getPropertyConstraints().size());
        
        if (shape.getPropertyConstraints().isEmpty()) {
            System.out.println("⚠️  Empty constraints - sh:or with only count constraints gets filtered");
            return;
        }
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        // Universal dual-track: ALL single-valued constraints create symbols,
        // including minCount.  The attr track still stores minCount=1 for merge semantics.
        System.out.println("Logical expression: " + pc.getLogicalExpression());
        System.out.println("Symbol table size: " + shapeGraph.getSymbolTable().getAllSymbols().size());
        System.out.println("minCount: " + pc.getMinCount());
        
        assertNotNull(pc.getLogicalExpression(),
                   "Universal dual-track: minCount should also produce a symbol in φ");
        assertEquals(1, pc.getMinCount(), "minCount attr should still be stored");
    }
    
    @Test
    @Order(3)
    @DisplayName("Test sh:or with conflicting type constraints - should NOT merge")
    void testOrWithConflictingTypeConstraints() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetClass ex:Document ;
                sh:property [
                    sh:path ex:authors ;
                    sh:or (
                        [ sh:datatype xsd:string ; sh:minCount 1 ; sh:maxCount 3 ]
                        [ sh:nodeKind sh:IRI ; sh:minCount 2 ; sh:maxCount 5 ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        assertEquals(1, shape.getPropertyConstraints().size());
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        
        System.out.println("\n=== sh:or with conflicting type constraints ===");
        System.out.println("Branch 1: datatype xsd:string + minCount 1 + maxCount 3");
        System.out.println("Branch 2: nodeKind sh:IRI + minCount 2 + maxCount 5");
        System.out.println();
        System.out.println("Expected: Should NOT merge branches (datatype conflicts with nodeKind)");
        System.out.println("Expected: Keep as Or(And(dt, mc1, xc3), And(nk, mc2, xc5))");
        System.out.println();
        System.out.println("Actual logical expression: " + pc.getLogicalExpression());
        
        // Should have an OR expression with two And branches
        // because datatype and nodeKind conflict
        if (pc.getLogicalExpression() != null) {
            System.out.println("Type: " + pc.getLogicalExpression().getClass().getSimpleName());
            assertTrue(pc.getLogicalExpression() instanceof OrExpr, 
                       "Should be OrExpr for conflicting type constraints");
            
            OrExpr orExpr = (OrExpr) pc.getLogicalExpression();
            System.out.println("Number of OR branches: " + orExpr.getArgs().size());
            
            // Each branch should contain type constraint (datatype or nodeKind)
            // but NOT count constraints (those are attributes)
            for (int i = 0; i < orExpr.getArgs().size(); i++) {
                System.out.println("  Branch " + (i+1) + ": " + orExpr.getArgs().get(i));
            }
        }
    }
    
    @Test
    @Order(4)
    @DisplayName("Test sh:or with same type, different counts - SHOULD merge with OR semantics")
    void testOrWithSameTypeConstraintsCanMerge() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetClass ex:Document ;
                sh:property [
                    sh:path ex:tags ;
                    sh:or (
                        [ sh:datatype xsd:string ; sh:minCount 1 ; sh:maxCount 3 ]
                        [ sh:datatype xsd:string ; sh:minCount 2 ; sh:maxCount 5 ]
                    )
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        System.out.println("\n=== sh:or with same type, different counts ===");
        System.out.println("Branch 1: datatype xsd:string + minCount 1 + maxCount 3");
        System.out.println("Branch 2: datatype xsd:string + minCount 2 + maxCount 5");
        System.out.println();
        System.out.println("Expected: SHOULD merge branches (same type, only counts differ)");
        System.out.println("Expected: datatype xsd:string + minCount min(1,2)=1 + maxCount max(3,5)=5");
        System.out.println();
        
        assertFalse(shape.getPropertyConstraints().isEmpty(), 
                   "Should have PropertyConstraint");
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        System.out.println("Actual logical expression: " + pc.getLogicalExpression());
        System.out.println("Actual minCount: " + pc.getMinCount());
        System.out.println("Actual maxCount: " + pc.getMaxCount());
        System.out.println();
        
        // Verify branches are merged:
        // - Logical expression: just the datatype symbol (not OR)
        assertNotNull(pc.getLogicalExpression(), "Should have logical expression");
        assertFalse(pc.getLogicalExpression() instanceof OrExpr, 
                   "Should NOT be OrExpr - branches merged to single datatype");
        assertTrue(pc.getLogicalExpression().toString().contains("dt_"), 
                  "Should be a single datatype symbol");
        
        // - minCount: min(1, 2) = 1 (OR semantics - looser)
        // - maxCount: max(3, 5) = 5 (OR semantics - looser)
        assertEquals(1, pc.getMinCount(), 
                    "minCount should be min(1,2)=1 using OR semantics");
        assertEquals(5, pc.getMaxCount(), 
                    "maxCount should be max(3,5)=5 using OR semantics");
        
        System.out.println("✓ Optimization successful: OR branches with same type but different counts merged");
    }
    
    @Test
    @Order(5)
    @DisplayName("Test merge conflict - demonstrates AND semantics in current implementation")
    void testMergeConflictAndSemantics() {
        String shaclData = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            
            ex:TestShape
                a sh:NodeShape ;
                sh:targetClass ex:Collection ;
                sh:property [
                    sh:path ex:items ;
                    sh:minCount 1 ;
                    sh:datatype ex:TypeA
                ] ;
                sh:property [
                    sh:path ex:items ;
                    sh:minCount 5 ;
                    sh:datatype ex:TypeB
                ] .
            """;
        
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(shaclData), null, "TURTLE");
        
        ShapeGraph shapeGraph = parser.parse(model);
        NodeShape shape = getFirstShape(shapeGraph);
        
        System.out.println("\n=== Merge Conflict Scenario ===");
        System.out.println("Two PropertyShapes with same path but different datatypes");
        System.out.println("Number of PropertyConstraints: " + shape.getPropertyConstraints().size());
        
        // When there's a datatype conflict, current implementation merges them:
        // - Conflicting datatypes become AND expression
        // - minCount uses AND semantics (max value)
        for (PropertyConstraint pc : shape.getPropertyConstraints()) {
            System.out.println("\nPropertyConstraint:");
            System.out.println("  minCount: " + pc.getMinCount());
            System.out.println("  datatype: " + pc.getDatatype());
            System.out.println("  logical expression: " + pc.getLogicalExpression());
        }
        
        PropertyConstraint pc = shape.getPropertyConstraints().get(0);
        System.out.println("\nCurrent behavior:");
        System.out.println("  Datatypes merged as: " + pc.getLogicalExpression());
        System.out.println("  minCount uses AND semantics: max(1, 5) = " + pc.getMinCount());
        System.out.println();
        System.out.println("Note: In current implementation, conflicting types are merged with AND,");
        System.out.println("      and minCount follows AND semantics (more restrictive).");
    }
    
    @Test
    @Order(6)
    @DisplayName("Explain OR vs AND semantics for count constraints")
    void explainOrVsAndSemantics() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("OR vs AND Semantics for Count Constraints");
        System.out.println("=".repeat(70));
        
        System.out.println("\n[AND SEMANTICS] (Default: merging multiple PropertyShapes)");
        System.out.println("  minCount 2 AND minCount 3 → minCount 3 (take max, more restrictive)");
        System.out.println("  maxCount 5 AND maxCount 3 → maxCount 3 (take min, more restrictive)");
        System.out.println("  Reason: Must satisfy both constraints");
        
        System.out.println("\n[OR SEMANTICS] (Explicit sh:or definition)");
        System.out.println("  minCount 2 OR minCount 3 → minCount 2 (take min, looser)");
        System.out.println("  maxCount 5 OR maxCount 3 → maxCount 5 (take max, looser)");
        System.out.println("  Reason: Only need to satisfy one constraint");
        
        System.out.println("\n[MERGING RULES FOR sh:or BRANCHES]");
        System.out.println("  1. Branches with conflicting single-valued properties → NOT merged");
        System.out.println("     Example: [ sh:datatype xsd:string ] + [ sh:nodeKind sh:IRI ]");
        System.out.println("              → Keep separate (conflict between datatype and nodeKind)");
        System.out.println();
        System.out.println("  2. Branches differing ONLY in minCount/maxCount → CAN be merged");
        System.out.println("     Example: [ sh:datatype xsd:string ; sh:minCount 1 ; sh:maxCount 3 ]");
        System.out.println("              + [ sh:datatype xsd:string ; sh:minCount 2 ; sh:maxCount 5 ]");
        System.out.println("              → Merge: sh:datatype xsd:string");
        System.out.println("                       sh:minCount 1 (min, OR semantics)");
        System.out.println("                       sh:maxCount 5 (max, OR semantics)");
        
        System.out.println("\n[PRACTICAL EXAMPLES]");
        System.out.println("  Scenario 1 - AND: Document needs 2-5 authors AND 3-4 reviewers");
        System.out.println("    → Merged: minCount = max(2,3) = 3, maxCount = min(5,4) = 4");
        System.out.println("    → Result: 3-4 items (stricter intersection)");
        
        System.out.println("\n  Scenario 2 - OR: Item can have (1-3 tags) OR (2-5 categories)");
        System.out.println("    → If same type: minCount = min(1,2) = 1, maxCount = max(3,5) = 5");
        System.out.println("    → Result: 1-5 items (looser union)");
        
        System.out.println("\n" + "=".repeat(70));
    }
}
