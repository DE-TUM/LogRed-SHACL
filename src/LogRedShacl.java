//package simshapes;

import model.ShapeGraph;
import model.ConflictReport;
import parser.ShaclParser;
import reducer.ShapeReducer;
import serializer.TurtleSerializer;
import org.apache.jena.riot.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * LogRed-SHACL - High-performance SHACL Shape Graph Simplifier
 * 
 * This tool simplifies SHACL shape graphs by applying logical simplification
 * techniques. Constraints are converted to symbolic logical expressions,
 * simplified using boolean algebra, and then serialized back to SHACL.
 */
@Command(
    name = "logred-shacl",
    mixinStandardHelpOptions = true,
    version = "LogRed-SHACL 0.1.0",
    description = "High-performance SHACL Shape Graph Simplifier using logical expression reduction."
)
public class LogRedShacl implements Callable<Integer> {
    
    private static final Logger log = LoggerFactory.getLogger(LogRedShacl.class);
    
    @Parameters(index = "0", description = "Input SHACL shapes file (TTL or RDF/XML)")
    private Path inputFile;
    
    @Option(names = {"-o", "--output"}, description = "Output file path (default: input_simplified.ttl)")
    private Path outputFile;
    
    @Option(names = {"--conflict-report"}, description = "Conflict report output path (default: input_conflicts.ttl). " +
            "Generated only when unsatisfiable constraints are detected.")
    private Path conflictReportFile;
    
    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    private boolean verbose;
    
    @Option(names = {"--stats"}, description = "Show simplification statistics")
    private boolean showStats;
    
    @Option(names = {"--format"}, description = "Output format: ntriples (default, fastest), turtle-flat, turtle-pretty, turtle-blocks",
            defaultValue = "ntriples")
    private String format;
    
    @Option(names = {"--parallel"}, description = "Enable parallel simplification (default: false)")
    private boolean parallel;
    
    @Option(names = {"-j", "--threads"}, description = "Number of threads for parallel simplification (default: available processors)")
    private Integer threads;
    
    @Option(names = {"--no-pattern-cache"}, description = "Disable pattern-based caching (default: enabled)")
    private boolean noPatternCache;
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogRedShacl()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public Integer call() throws Exception {
        // Validate input
        if (!Files.exists(inputFile)) {
            log.error("Input file does not exist: {}", inputFile);
            return 1;
        }
        
        // Resolve output format
        RDFFormat rdfFormat = switch (format.toLowerCase()) {
            case "turtle-pretty", "turtle_pretty", "pretty" -> RDFFormat.TURTLE_PRETTY;
            case "turtle-flat", "turtle_flat", "flat" -> RDFFormat.TURTLE_FLAT;
            case "turtle-blocks", "turtle_blocks", "blocks" -> RDFFormat.TURTLE_BLOCKS;
            case "turtle", "ttl" -> RDFFormat.TURTLE_FLAT;
            default -> RDFFormat.NTRIPLES;
        };
        String ext = (rdfFormat == RDFFormat.NTRIPLES) ? ".nt" : ".ttl";
        
        // Default output file
        if (outputFile == null) {
            String inputName = inputFile.getFileName().toString();
            String baseName = inputName.contains(".") ? 
                inputName.substring(0, inputName.lastIndexOf('.')) : inputName;
            outputFile = inputFile.getParent().resolve(baseName + "_simplified" + ext);
        }
        
        // Default conflict report file
        if (conflictReportFile == null) {
            String inputName = inputFile.getFileName().toString();
            String baseName = inputName.contains(".") ? 
                inputName.substring(0, inputName.lastIndexOf('.')) : inputName;
            conflictReportFile = inputFile.getParent().resolve(baseName + "_conflicts" + ext);
        }
        
        log.info("═══════════════════════════════════════════════════════");
        log.info("LogRed-SHACL - SHACL Shape Graph Simplifier v2.0.0");
        log.info("═══════════════════════════════════════════════════════");
        log.info("Input:    {}", inputFile);
        log.info("Output:   {}", outputFile);
        log.info("═══════════════════════════════════════════════════════");
        
        try {
            // Step 1: Parse SHACL shapes and build logical expressions
            log.info("[STEP 1] Parsing SHACL shapes and building logical expressions...");
            long parseStart = System.currentTimeMillis();
            
            ShaclParser parser = new ShaclParser();
            ShapeGraph shapeGraph = parser.parse(inputFile);
            
            long parseTime = System.currentTimeMillis() - parseStart;
            log.info("Parsed {} shapes with {} symbols in {}ms", 
                    shapeGraph.getNodeShapes().size(), 
                    shapeGraph.getSymbolCount(),
                    parseTime);
            
            // Step 2: Reduce (merge + simplify + optimize)
            log.info("[STEP 2] Reducing constraints...");
            long reduceStart = System.currentTimeMillis();
            
            ShapeReducer reducer = new ShapeReducer()
                .setPatternCacheEnabled(!noPatternCache)
                .setParallelEnabled(parallel);
            
            if (threads != null && threads > 0) {
                reducer.setParallelism(threads);
            }
            
            if (parallel) {
                int actualThreads = threads != null ? threads : Runtime.getRuntime().availableProcessors();
                log.info("Parallel mode: {} threads, pattern cache: {}", actualThreads, !noPatternCache);
            }
            
            reducer.reduce(shapeGraph);
            
            long reduceTime = System.currentTimeMillis() - reduceStart;
            log.info("Reduction completed in {}ms", reduceTime);
            
            // Step 3: Serialize simplified shapes
            log.info("[STEP 3] Serializing simplified shapes...");
            long serializeStart = System.currentTimeMillis();
            
            TurtleSerializer serializer = new TurtleSerializer()
                .setOutputFormat(rdfFormat);
            serializer.serialize(shapeGraph, outputFile);
            
            long serializeTime = System.currentTimeMillis() - serializeStart;
            log.info("Serialization completed in {}ms", serializeTime);
            
            // Step 4: Generate conflict report if there are unsatisfiable constraints
            ConflictReport conflictReport = reducer.getConflictReport();
            long conflictReportTime = 0;
            
            if (conflictReport != null && conflictReport.hasConflicts()) {
                log.info("[STEP 4] Generating conflict report...");
                long conflictStart = System.currentTimeMillis();
                
                ShapeGraph conflictGraph = conflictReport.toShapeGraph(shapeGraph.getSymbolTable());
                TurtleSerializer conflictSerializer = new TurtleSerializer()
                    .setOutputFormat(rdfFormat);
                conflictSerializer.serialize(conflictGraph, conflictReportFile);
                
                conflictReportTime = System.currentTimeMillis() - conflictStart;
                log.info("Conflict report generated in {}ms", conflictReportTime);
            }
            
            // Summary
            long totalTime = parseTime + reduceTime + serializeTime + conflictReportTime;
            log.info("═══════════════════════════════════════════════════════");
            log.info("SUMMARY");
            log.info("═══════════════════════════════════════════════════════");
            log.info("Parse time:     {}ms", parseTime);
            log.info("Reduce time:    {}ms", reduceTime);
            log.info("Serialize time: {}ms", serializeTime);
            if (conflictReportTime > 0) {
                log.info("Conflict report: {}ms", conflictReportTime);
            }
            log.info("Total time:     {}ms", totalTime);
            log.info("Symbols:        {}", shapeGraph.getSymbolCount());
            
            if (conflictReport != null && conflictReport.hasConflicts()) {
                log.info("───────────────────────────────────────────────────────");
                log.info("CONFLICTS: {} unsatisfiable PropertyShape(s) in {} NodeShape(s)",
                        conflictReport.getTotalConflicts(), conflictReport.getAffectedShapeCount());
                log.info("Conflict report: {}", conflictReportFile);
            }
            
            // Show cache statistics if --stats flag is enabled
            if (showStats && reducer.getSimplifier() != null) {
                var cacheStats = reducer.getSimplifier().getCacheStats();
                log.info("Cache stats:    {}", cacheStats);
            }
            
            log.info("Output written to: {}", outputFile);
            
            return 0;
            
        } catch (Exception e) {
            log.error("Error during simplification: {}", e.getMessage(), e);
            return 1;
        }
    }
}
