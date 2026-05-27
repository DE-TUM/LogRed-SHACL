package model;

import java.util.*;

/**
 * Records PropertyShapes that simplified to FALSE (unsatisfiable constraints).
 * 
 * These shapes are removed from the simplified output to avoid the semantic error
 * of serializing FALSE as an empty PropertyShape (which would mean TRUE).
 * Instead, they are collected here and can be serialized as a separate SHACL file
 * — the "conflict report".
 * 
 * The conflict report is a valid SHACL Turtle file containing:
 * - NodeShapes (with targets) that had at least one FALSE PropertyShape
 * - Only the FALSE PropertyShapes in their ORIGINAL form (before simplification)
 * 
 * Design: conflict report + simplified output = full coverage of original shape graph.
 */
public class ConflictReport {
    
    /**
     * A single conflict entry: one PropertyConstraint that became FALSE,
     * stored in its original (pre-simplification) form.
     */
    public record ConflictEntry(
        PropertyConstraint originalConstraint,
        String reason
    ) {}
    
    /**
     * Map: NodeShape URI → list of conflict entries for that shape.
     * Uses LinkedHashMap to preserve insertion order.
     */
    private final Map<String, List<ConflictEntry>> entriesByShape = new LinkedHashMap<>();
    
    /**
     * Cache of NodeShape metadata (URI + targets) for report serialization.
     * We store a lightweight copy of the NodeShape (no PropertyConstraints).
     */
    private final Map<String, NodeShape> shapeMetadata = new LinkedHashMap<>();
    
    /**
     * Record a conflict: a PropertyConstraint that simplified to FALSE.
     * 
     * @param shape the NodeShape that contains this constraint
     * @param originalPC the PropertyConstraint in its ORIGINAL form (before any optimization)
     * @param reason human-readable description of why it became FALSE
     */
    public void addConflict(NodeShape shape, PropertyConstraint originalPC, String reason) {
        String shapeUri = shape.getUri();
        
        // Store shape metadata (targets) if not already stored
        if (!shapeMetadata.containsKey(shapeUri)) {
            NodeShape metadata = new NodeShape(shapeUri);
            for (var tc : shape.getTargetClasses()) metadata.addTargetClass(tc);
            for (var tn : shape.getTargetNodes()) metadata.addTargetNode(tn);
            for (var ts : shape.getTargetSubjectsOf()) metadata.addTargetSubjectsOf(ts);
            for (var to : shape.getTargetObjectsOf()) metadata.addTargetObjectsOf(to);
            shapeMetadata.put(shapeUri, metadata);
        }
        
        entriesByShape
            .computeIfAbsent(shapeUri, k -> new ArrayList<>())
            .add(new ConflictEntry(originalPC, reason));
    }
    
    /**
     * Check if there are any conflicts recorded.
     */
    public boolean hasConflicts() {
        return !entriesByShape.isEmpty();
    }
    
    /**
     * Get the total number of conflicting PropertyShapes.
     */
    public int getTotalConflicts() {
        return entriesByShape.values().stream().mapToInt(List::size).sum();
    }
    
    /**
     * Get the number of NodeShapes affected by conflicts.
     */
    public int getAffectedShapeCount() {
        return entriesByShape.size();
    }
    
    /**
     * Get all conflict entries grouped by NodeShape URI.
     */
    public Map<String, List<ConflictEntry>> getEntriesByShape() {
        return Collections.unmodifiableMap(entriesByShape);
    }
    
    /**
     * Get the NodeShape metadata (URI + targets) for a given shape URI.
     */
    public NodeShape getShapeMetadata(String shapeUri) {
        return shapeMetadata.get(shapeUri);
    }
    
    /**
     * Get all NodeShape metadata entries.
     */
    public Collection<NodeShape> getAllShapeMetadata() {
        return shapeMetadata.values();
    }
    
    /**
     * Build a ShapeGraph containing only the conflict shapes for serialization.
     * Each NodeShape in the returned graph contains only its conflicting PropertyConstraints
     * in their original form.
     * 
     * @param symbolTable the SymbolTable from the original ShapeGraph (needed for serialization)
     */
    public ShapeGraph toShapeGraph(logic.SymbolTable symbolTable) {
        ShapeGraph conflictGraph = new ShapeGraph();
        conflictGraph.setSymbolTable(symbolTable);
        
        for (Map.Entry<String, List<ConflictEntry>> entry : entriesByShape.entrySet()) {
            String shapeUri = entry.getKey();
            List<ConflictEntry> conflicts = entry.getValue();
            NodeShape metadata = shapeMetadata.get(shapeUri);
            
            if (metadata == null) continue;
            
            // Create a NodeShape with targets + only the conflicting PCs
            NodeShape reportShape = new NodeShape(shapeUri);
            for (var tc : metadata.getTargetClasses()) reportShape.addTargetClass(tc);
            for (var tn : metadata.getTargetNodes()) reportShape.addTargetNode(tn);
            for (var ts : metadata.getTargetSubjectsOf()) reportShape.addTargetSubjectsOf(ts);
            for (var to : metadata.getTargetObjectsOf()) reportShape.addTargetObjectsOf(to);
            
            for (ConflictEntry conflict : conflicts) {
                reportShape.addPropertyConstraint(conflict.originalConstraint());
            }
            
            conflictGraph.addNodeShape(reportShape);
        }
        
        return conflictGraph;
    }
    
    /**
     * Get a human-readable summary of all conflicts.
     */
    public String getSummary() {
        if (!hasConflicts()) {
            return "No constraint conflicts detected.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Constraint conflicts: %d unsatisfiable PropertyShape(s) in %d NodeShape(s)\n",
                getTotalConflicts(), getAffectedShapeCount()));
        
        for (Map.Entry<String, List<ConflictEntry>> entry : entriesByShape.entrySet()) {
            sb.append(String.format("  Shape: %s\n", entry.getKey()));
            for (ConflictEntry conflict : entry.getValue()) {
                String path = conflict.originalConstraint().getPath() != null
                    ? conflict.originalConstraint().getPath().toString()
                    : "<unknown>";
                sb.append(String.format("    Path: %s — %s\n", path, conflict.reason()));
            }
        }
        
        return sb.toString();
    }
}
