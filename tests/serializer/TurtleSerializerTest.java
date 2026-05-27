package serializer;

import model.*;
import logic.*;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.RDFFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TurtleSerializer including streaming serialization.
 */
class TurtleSerializerTest {
    
    @TempDir
    Path tempDir;
    
    private ShapeGraph createTestShapeGraph(int numShapes) {
        ShapeGraph graph = new ShapeGraph();
        SymbolTable table = new SymbolTable();
        
        for (int i = 0; i < numShapes; i++) {
            String shapeUri = "http://example.org/Shape" + i;
            
            NodeShape shape = new NodeShape(shapeUri);
            shape.addTargetClass(ResourceFactory.createResource("http://example.org/Class" + i));
            
            // Add a simple property constraint
            PropertyConstraint pc = new PropertyConstraint(
                ResourceFactory.createProperty("http://example.org/prop" + i));
            pc.setMinCount(1);
            shape.addPropertyConstraint(pc);
            
            graph.addNodeShape(shape);
        }
        
        graph.setSymbolTable(table);
        return graph;
    }
    
    @Test
    void testSerializeToFile() throws IOException {
        ShapeGraph graph = createTestShapeGraph(5);
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        
        Path outputPath = tempDir.resolve("output.ttl");
        serializer.serialize(graph, outputPath);
        
        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("sh:targetClass"));
        assertTrue(content.contains("Shape0"));
    }
    
    @Test
    void testSerializeToString() {
        ShapeGraph graph = createTestShapeGraph(3);
        TurtleSerializer serializer = new TurtleSerializer()
            .setOutputFormat(RDFFormat.TURTLE_PRETTY);
        
        String content = serializer.serializeToString(graph);
        
        assertNotNull(content);
        assertTrue(content.contains("sh:targetClass"));
        assertTrue(content.contains("Shape0"));
        assertTrue(content.contains("Shape1"));
        assertTrue(content.contains("Shape2"));
    }
    
    @Test
    void testStreamingSerialize() throws IOException {
        ShapeGraph graph = createTestShapeGraph(50);
        TurtleSerializer serializer = new TurtleSerializer();
        serializer.setStreamingBatchSize(10);  // Small batch for testing
        
        Path outputPath = tempDir.resolve("streaming_output.ttl");
        serializer.serializeStreaming(graph, outputPath);
        
        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        
        // Verify prefixes are present
        assertTrue(content.contains("@prefix sh:"));
        
        // Verify shapes are present
        assertTrue(content.contains("Shape0"));
        assertTrue(content.contains("Shape49"));
    }
    
    @Test
    void testStreamingSerializeLargeGraph() throws IOException {
        // Test with a larger graph to verify memory efficiency
        ShapeGraph graph = createTestShapeGraph(500);
        TurtleSerializer serializer = new TurtleSerializer();
        serializer.setStreamingBatchSize(100);
        
        Path outputPath = tempDir.resolve("large_output.ttl");
        serializer.serializeStreaming(graph, outputPath);
        
        assertTrue(Files.exists(outputPath));
        long fileSize = Files.size(outputPath);
        assertTrue(fileSize > 0);
        
        // Verify first and last shapes are present
        String content = Files.readString(outputPath);
        assertTrue(content.contains("Shape0"));
        assertTrue(content.contains("Shape499"));
    }
    
    @Test
    void testStreamingVsNormalProducesSameShapes() throws IOException {
        ShapeGraph graph = createTestShapeGraph(20);
        TurtleSerializer serializer = new TurtleSerializer();
        
        // Normal serialization
        Path normalPath = tempDir.resolve("normal.ttl");
        serializer.serialize(graph, normalPath);
        String normalContent = Files.readString(normalPath);
        
        // Streaming serialization
        Path streamingPath = tempDir.resolve("streaming.ttl");
        serializer.setStreamingBatchSize(5);
        serializer.serializeStreaming(graph, streamingPath);
        String streamingContent = Files.readString(streamingPath);
        
        // Both should contain all shapes (order may differ)
        for (int i = 0; i < 20; i++) {
            assertTrue(normalContent.contains("Shape" + i), "Normal output missing Shape" + i);
            assertTrue(streamingContent.contains("Shape" + i), "Streaming output missing Shape" + i);
        }
    }
    
    @Test
    void testBatchSizeConfiguration() {
        TurtleSerializer serializer = new TurtleSerializer();
        
        // Default should work
        serializer.setStreamingBatchSize(50);
        
        // Zero or negative should default to 100
        serializer.setStreamingBatchSize(0);
        serializer.setStreamingBatchSize(-10);
        
        // Should not throw
    }
    
    @Test
    void testEmptyShapeGraph() throws IOException {
        ShapeGraph graph = createTestShapeGraph(0);
        TurtleSerializer serializer = new TurtleSerializer();
        
        // Normal serialization
        Path normalPath = tempDir.resolve("empty_normal.ttl");
        serializer.serialize(graph, normalPath);
        assertTrue(Files.exists(normalPath));
        
        // Streaming serialization
        Path streamingPath = tempDir.resolve("empty_streaming.ttl");
        serializer.serializeStreaming(graph, streamingPath);
        assertTrue(Files.exists(streamingPath));
    }
}
