package model;

import logic.SymbolTable;
import org.apache.jena.rdf.model.Model;
import java.util.*;

public class ShapeGraph {
    private final Map<String, NodeShape> nodeShapes;
    private SymbolTable symbolTable;
    private Model sourceModel;  // Original parsed Jena model (for deep-copy of blank nodes)
    
    public ShapeGraph() {
        this.nodeShapes = new LinkedHashMap<>();
    }
    
    public void addNodeShape(NodeShape shape) {
        nodeShapes.put(shape.getUri(), shape);
    }
    
    public NodeShape getNodeShape(String uri) {
        return nodeShapes.get(uri);
    }
    
    public Collection<NodeShape> getNodeShapes() {
        return nodeShapes.values();
    }
    
    /**
     * Remove a NodeShape by its URI.
     * @return true if the shape was removed, false if it didn't exist
     */
    public boolean removeNodeShape(String uri) {
        return nodeShapes.remove(uri) != null;
    }
    
    public int getNodeShapeCount() {
        return nodeShapes.size();
    }
    
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }
    
    public void setSymbolTable(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }
    
    public Model getSourceModel() {
        return sourceModel;
    }
    
    public void setSourceModel(Model sourceModel) {
        this.sourceModel = sourceModel;
    }
    
    public int getSymbolCount() {
        return symbolTable != null ? symbolTable.getAllSymbols().size() : 0;
    }
    
    @Override
    public String toString() {
        return "ShapeGraph{nodeShapes=" + nodeShapes.size() + 
               ", symbols=" + getSymbolCount() + "}";
    }
}
