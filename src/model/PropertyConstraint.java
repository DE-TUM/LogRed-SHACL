package model;

import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import logic.LogicalExpression;
import java.util.*;

public class PropertyConstraint {
    private String uri;  // URI of the PropertyShape (null if blank node)
    private Resource path;
    private final Map<ConstraintType, Object> constraints;
    
    /**
     * Logical expression for complex constraints (sh:or, sh:and, sh:not, sh:xone).
     * This may be simplified/optimized during processing.
     */
    private LogicalExpression logicalExpression;
    
    // Set of in-constraint symbol names (for sh:in values)
    private Set<String> inSymbols = new HashSet<>();
    
    public enum ConstraintType {
        MIN_COUNT, MAX_COUNT,
        DATATYPE, CLASS, NODE_KIND,
        MIN_LENGTH, MAX_LENGTH,
        PATTERN, FLAGS,
        IN, LANGUAGE_IN, HAS_VALUE,
        MIN_INCLUSIVE, MAX_INCLUSIVE, MIN_EXCLUSIVE, MAX_EXCLUSIVE,
        UNIQUE_LANG, CLOSED, IGNORED_PROPERTIES,
        EQUALS, DISJOINT, LESS_THAN, LESS_THAN_OR_EQUALS,
        NOT, AND, OR, XONE,
        NODE, QUALIFIED_VALUE_SHAPE,
        QUALIFIED_MIN_COUNT, QUALIFIED_MAX_COUNT,
        QUALIFIED_VALUE_SHAPES_DISJOINT
    }
    
    public PropertyConstraint() {
        this.constraints = new EnumMap<>(ConstraintType.class);
    }
    
    public PropertyConstraint(Resource path) {
        this.path = path;
        this.constraints = new EnumMap<>(ConstraintType.class);
    }
    
    public PropertyConstraint(String uri, Resource path) {
        this.uri = uri;
        this.path = path;
        this.constraints = new EnumMap<>(ConstraintType.class);
    }
    
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public boolean hasUri() { return uri != null && !uri.isEmpty() && !uri.startsWith("_:"); }
    
    public Resource getPath() { return path; }
    public void setPath(Resource path) { this.path = path; }
    
    public Map<ConstraintType, Object> getConstraints() { return constraints; }
    
    public void setConstraint(ConstraintType type, Object value) {
        constraints.put(type, value);
    }
    
    public Object getConstraint(ConstraintType type) {
        return constraints.get(type);
    }
    
    public boolean hasConstraint(ConstraintType type) {
        return constraints.containsKey(type);
    }
    
    public void removeConstraint(ConstraintType type) {
        constraints.remove(type);
    }
    
    // Logical expression methods
    public LogicalExpression getLogicalExpression() { return logicalExpression; }
    public void setLogicalExpression(LogicalExpression expr) { this.logicalExpression = expr; }
    public boolean hasLogicalExpression() { return logicalExpression != null; }


    
    // In symbols methods
    public Set<String> getInSymbols() { return inSymbols; }
    public void setInSymbols(Set<String> symbols) { this.inSymbols = symbols; }
    public void addInSymbol(String symbol) { this.inSymbols.add(symbol); }
    
    public Integer getMinCount() { return (Integer) constraints.get(ConstraintType.MIN_COUNT); }
    public void setMinCount(Integer value) { constraints.put(ConstraintType.MIN_COUNT, value); }
    
    public Integer getMaxCount() { return (Integer) constraints.get(ConstraintType.MAX_COUNT); }
    public void setMaxCount(Integer value) { constraints.put(ConstraintType.MAX_COUNT, value); }
    
    public Resource getDatatype() { return (Resource) constraints.get(ConstraintType.DATATYPE); }
    public void setDatatype(Resource value) { constraints.put(ConstraintType.DATATYPE, value); }
    
    public Resource getNodeKind() { return (Resource) constraints.get(ConstraintType.NODE_KIND); }
    public void setNodeKind(Resource value) { constraints.put(ConstraintType.NODE_KIND, value); }
    
    @SuppressWarnings("unchecked")
    public List<RDFNode> getIn() { return (List<RDFNode>) constraints.get(ConstraintType.IN); }
    
    public boolean isEmpty() { 
        return constraints.isEmpty() && logicalExpression == null
            && inSymbols.isEmpty(); 
    }
    
    public PropertyConstraint copy() {
        PropertyConstraint copy = new PropertyConstraint(uri, path);
        copy.constraints.putAll(this.constraints);
        copy.logicalExpression = this.logicalExpression != null ? this.logicalExpression.copy() : null;
        copy.inSymbols = new HashSet<>(this.inSymbols);
        return copy;
    }
    
    @Override
    public String toString() {
        String pathName = path != null ? path.getLocalName() : "null";
        return "PropertyConstraint{uri=" + uri + ", path=" + pathName + 
               ", constraints=" + constraints.keySet() + 
               ", logical=" + (logicalExpression != null ? logicalExpression : "none") + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PropertyConstraint that = (PropertyConstraint) o;
        return Objects.equals(uri, that.uri) && 
               Objects.equals(path, that.path) && 
               Objects.equals(constraints, that.constraints) &&
               Objects.equals(logicalExpression, that.logicalExpression);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(uri, path, constraints, logicalExpression);
    }
}
