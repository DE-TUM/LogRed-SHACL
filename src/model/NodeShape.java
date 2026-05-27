package model;

import org.apache.jena.rdf.model.Resource;
import java.util.*;

public class NodeShape {
    private final String uri;
    private final Set<Resource> targetClasses;
    private final Set<Resource> targetNodes;
    private final Set<Resource> targetSubjectsOf;
    private final Set<Resource> targetObjectsOf;
    private final List<PropertyConstraint> propertyConstraints;
    private final List<LogicalConstraint> logicalConstraints;
    
    // sh:closed and sh:ignoredProperties (NodeShape-level constraints)
    private Boolean closed;  // null = not present, true/false = explicit value
    private List<Resource> ignoredProperties;

    // sh:hasClass (non-standard, used by MagicShapes.jar magic seeds)
    private Resource hasClass;

    // sh:node at NodeShape level (pass-through, not simplified)
    private final List<Resource> nodeRefs = new ArrayList<>();
    
    public NodeShape(String uri) {
        this.uri = uri;
        this.targetClasses = new HashSet<>();
        this.targetNodes = new HashSet<>();
        this.targetSubjectsOf = new HashSet<>();
        this.targetObjectsOf = new HashSet<>();
        this.propertyConstraints = new ArrayList<>();
        this.logicalConstraints = new ArrayList<>();
        this.ignoredProperties = new ArrayList<>();
    }
    
    public String getUri() { return uri; }
    
    public Set<Resource> getTargetClasses() { return targetClasses; }
    public Set<Resource> getTargetNodes() { return targetNodes; }
    public Set<Resource> getTargetSubjectsOf() { return targetSubjectsOf; }
    public Set<Resource> getTargetObjectsOf() { return targetObjectsOf; }
    
    public List<PropertyConstraint> getPropertyConstraints() { return propertyConstraints; }
    public List<LogicalConstraint> getLogicalConstraints() { return logicalConstraints; }
    
    public void addTargetClass(Resource target) { targetClasses.add(target); }
    public void addTargetNode(Resource target) { targetNodes.add(target); }
    public void addTargetSubjectsOf(Resource target) { targetSubjectsOf.add(target); }
    public void addTargetObjectsOf(Resource target) { targetObjectsOf.add(target); }
    
    public void addPropertyConstraint(PropertyConstraint constraint) { propertyConstraints.add(constraint); }
    public void addLogicalConstraint(LogicalConstraint constraint) { logicalConstraints.add(constraint); }
    public void clearPropertyConstraints() { propertyConstraints.clear(); }
    
    // sh:closed
    public Boolean getClosed() { return closed; }
    public void setClosed(Boolean closed) { this.closed = closed; }
    public boolean isClosed() { return closed != null && closed; }
    public boolean hasClosedConstraint() { return closed != null; }
    
    // sh:ignoredProperties
    public List<Resource> getIgnoredProperties() { return ignoredProperties; }
    public void setIgnoredProperties(List<Resource> ignoredProperties) { this.ignoredProperties = ignoredProperties; }
    public void addIgnoredProperty(Resource prop) { this.ignoredProperties.add(prop); }
    
    // sh:hasClass
    public Resource getHasClass() { return hasClass; }
    public void setHasClass(Resource hasClass) { this.hasClass = hasClass; }

    // sh:node (NodeShape level)
    public List<Resource> getNodeRefs() { return nodeRefs; }
    public void addNodeRef(Resource ref) { nodeRefs.add(ref); }

    public boolean hasTargets() {
        return !targetClasses.isEmpty() || !targetNodes.isEmpty() 
            || !targetSubjectsOf.isEmpty() || !targetObjectsOf.isEmpty();
    }
    
    @Override
    public String toString() {
        return "NodeShape{" + uri + ", constraints=" + (propertyConstraints.size() + logicalConstraints.size()) + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeShape nodeShape = (NodeShape) o;
        return Objects.equals(uri, nodeShape.uri);
    }
    
    @Override
    public int hashCode() { return Objects.hash(uri); }
}
