package reducer;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Structural equivalence checker for RDFNodes.
 * 
 * Two RDFNodes are structurally equivalent if:
 * - Both are URI resources with the same URI, or
 * - Both are literals with the same value/datatype/language, or
 * - Both are blank nodes with the same set of (property, object) pairs
 *   (recursively checked for blank node objects).
 * 
 * This is used for sh:xone alternative deduplication, where blank nodes
 * represent anonymous shapes like [ sh:class ex:Student ].
 */
public class RDFNodeEquivalence {
    private static final Logger log = LoggerFactory.getLogger(RDFNodeEquivalence.class);
    
    /**
     * Check if two RDFNodes are structurally equivalent.
     * Handles URI resources, literals, blank nodes (recursive), and RDF lists.
     * 
     * @param a first node
     * @param b second node
     * @param sourceModel the model containing the nodes' properties (for blank node traversal)
     * @return true if structurally equivalent
     */
    public static boolean isEquivalent(RDFNode a, RDFNode b, Model sourceModel) {
        return isEquivalent(a, b, sourceModel, new HashSet<>());
    }
    
    private static boolean isEquivalent(RDFNode a, RDFNode b, Model sourceModel, Set<String> visited) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        
        // Same object
        if (a.equals(b)) return true;
        
        // URI resources: compare URIs
        if (a.isURIResource() && b.isURIResource()) {
            return a.asResource().getURI().equals(b.asResource().getURI());
        }
        
        // Literals: compare value, datatype, language
        if (a.isLiteral() && b.isLiteral()) {
            return a.asLiteral().equals(b.asLiteral());
        }
        
        // Both must be blank nodes from here
        if (!a.isAnon() || !b.isAnon()) return false;
        
        // Cycle detection: track visited blank node pairs
        String pairKey = a.asResource().getId().toString() + "|" + b.asResource().getId().toString();
        if (visited.contains(pairKey)) return true; // assume equivalent if already comparing
        visited.add(pairKey);
        
        Resource ra = a.asResource();
        Resource rb = b.asResource();
        
        // Collect all (property, object) pairs from both nodes
        List<Statement> stmtsA = sourceModel.listStatements(ra, null, (RDFNode) null).toList();
        List<Statement> stmtsB = sourceModel.listStatements(rb, null, (RDFNode) null).toList();
        
        if (stmtsA.size() != stmtsB.size()) return false;
        
        // Group by property URI
        Map<String, List<RDFNode>> propsA = groupByProperty(stmtsA);
        Map<String, List<RDFNode>> propsB = groupByProperty(stmtsB);
        
        if (!propsA.keySet().equals(propsB.keySet())) return false;
        
        for (String propUri : propsA.keySet()) {
            List<RDFNode> valuesA = propsA.get(propUri);
            List<RDFNode> valuesB = propsB.get(propUri);
            
            if (valuesA.size() != valuesB.size()) return false;
            
            // For single values, compare directly
            if (valuesA.size() == 1) {
                if (!isEquivalent(valuesA.get(0), valuesB.get(0), sourceModel, visited)) {
                    return false;
                }
            } else {
                // For multi-valued properties, try to find a matching permutation
                // Use greedy matching (sufficient for typical SHACL structures)
                boolean[] matched = new boolean[valuesB.size()];
                for (RDFNode va : valuesA) {
                    boolean found = false;
                    for (int j = 0; j < valuesB.size(); j++) {
                        if (!matched[j] && isEquivalent(va, valuesB.get(j), sourceModel, visited)) {
                            matched[j] = true;
                            found = true;
                            break;
                        }
                    }
                    if (!found) return false;
                }
            }
        }
        
        return true;
    }
    
    private static Map<String, List<RDFNode>> groupByProperty(List<Statement> stmts) {
        Map<String, List<RDFNode>> map = new LinkedHashMap<>();
        for (Statement stmt : stmts) {
            String propUri = stmt.getPredicate().getURI();
            map.computeIfAbsent(propUri, k -> new ArrayList<>()).add(stmt.getObject());
        }
        return map;
    }
    
    /**
     * BiPredicate adapter for use with XorOptimizer.simplifyXoneAlternatives().
     * 
     * @param sourceModel the model containing the blank nodes' properties
     * @return a BiPredicate that checks structural equivalence of two RDFNodes
     */
    public static java.util.function.BiPredicate<RDFNode, RDFNode> equivalenceChecker(Model sourceModel) {
        return (a, b) -> isEquivalent(a, b, sourceModel);
    }
    
    /**
     * BiPredicate adapter for Object operands (used in NodeShape-level LogicalConstraint).
     * Handles Resource operands from LogicalConstraint.getOperands().
     * 
     * @param sourceModel the model containing the blank nodes' properties
     * @return a BiPredicate that checks structural equivalence
     */
    public static java.util.function.BiPredicate<Object, Object> operandEquivalenceChecker(Model sourceModel) {
        return (a, b) -> {
            if (a instanceof RDFNode na && b instanceof RDFNode nb) {
                return isEquivalent(na, nb, sourceModel);
            }
            return Objects.equals(a, b);
        };
    }
}
