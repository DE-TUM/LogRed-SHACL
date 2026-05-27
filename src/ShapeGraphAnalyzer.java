import model.ShaclVocabulary;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fast SHACL shape-graph analyzer based on Jena's streaming parser.
 *
 * Counts:
 * - total triples/statements
 * - total supported constraint statements (per ShaclParser vocabulary)
 * - node shapes (same semantics as ShaclParser.findNodeShapes())
 * - targeted classes (distinct sh:targetClass objects)
 * - property shapes (explicit sh:PropertyShape + all sh:property objects)
 *
 * Optional:
 * - CSV output
 * - constraint-type breakdown
 */
public final class ShapeGraphAnalyzer {
    private static final Node RDF_TYPE = RDF.type.asNode();
    private static final Node SH_NODE_SHAPE = ShaclVocabulary.SH_NODE_SHAPE.asNode();
    private static final Node SH_PROPERTY_SHAPE = ShaclVocabulary.SH_PROPERTY_SHAPE.asNode();

    private static final Node SH_TARGET_CLASS = ShaclVocabulary.SH_TARGET_CLASS.asNode();
    private static final Node SH_TARGET_NODE = ShaclVocabulary.SH_TARGET_NODE.asNode();
    private static final Node SH_TARGET_SUBJECTS_OF = ShaclVocabulary.SH_TARGET_SUBJECTS_OF.asNode();
    private static final Node SH_TARGET_OBJECTS_OF = ShaclVocabulary.SH_TARGET_OBJECTS_OF.asNode();
    private static final Node SH_PROPERTY = ShaclVocabulary.SH_PROPERTY.asNode();
    private static final Node SH_PATH = ShaclVocabulary.SH_PATH.asNode();

    private static final Map<Node, String> CONSTRAINT_LABELS = createConstraintLabels();
    private static final Set<String> RDF_EXTENSIONS = Set.of(
            ".ttl", ".nt", ".n3", ".rdf", ".xml", ".jsonld", ".trig", ".nq"
    );

    private ShapeGraphAnalyzer() {}

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        if (options.showHelp || options.inputs.isEmpty()) {
            printUsage();
            System.exit(options.showHelp ? 0 : 1);
        }

        List<Path> files = expandInputs(options.inputs);
        if (files.isEmpty()) {
            System.err.println("No RDF shape graph files found.");
            System.exit(1);
        }

        List<GraphStats> stats = new ArrayList<>();
        boolean hadError = false;

        for (Path file : files) {
            try {
                stats.add(analyze(file));
            } catch (Exception e) {
                hadError = true;
                System.err.printf("ERROR %s: %s%n", file, e.getMessage());
            }
        }

        if (stats.isEmpty()) {
            System.err.println("No files could be analyzed.");
            System.exit(1);
        }

        if (options.csv) {
            printCsv(stats, options.breakdown);
        } else {
            printTable(stats);
            if (options.breakdown) {
                printBreakdown(stats);
            }
        }

        if (hadError) {
            System.exit(2);
        }
    }

    private static GraphStats analyze(Path path) {
        CountingStream stream = new CountingStream(path);
        RDFParser.source(path.toString())
                .errorHandler(ErrorHandlerFactory.errorHandlerNoWarnings)
                .parse(stream);
        return stream.toStats();
    }

    private static List<Path> expandInputs(List<String> inputs) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (String input : inputs) {
            Path path = Paths.get(input).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                System.err.printf("Skipping missing path: %s%n", path);
                continue;
            }
            if (Files.isRegularFile(path) && isRdfFile(path)) {
                files.add(path);
                continue;
            }
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(ShapeGraphAnalyzer::isRdfFile)
                            .sorted()
                            .forEach(files::add);
                }
                continue;
            }
            System.err.printf("Skipping unsupported path: %s%n", path);
        }
        return files.stream().sorted().collect(Collectors.toList());
    }

    private static boolean isRdfFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return RDF_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static void printTable(List<GraphStats> stats) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {
                "file", "triples", "constraints", "node_shapes", "targeted_classes", "property_shapes"
        });
        for (GraphStats s : stats) {
            rows.add(new String[] {
                    s.displayPath(),
                    formatLong(s.triples()),
                    formatLong(s.constraints()),
                    formatLong(s.nodeShapes()),
                    formatLong(s.targetedClasses()),
                    formatLong(s.propertyShapes()),
            });
        }

        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }

        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                boolean leftAlign = i == 0;
                if (i > 0) {
                    line.append("  ");
                }
                line.append(pad(row[i], widths[i], leftAlign));
            }
            System.out.println(line);
            if (r == 0) {
                StringBuilder divider = new StringBuilder();
                for (int i = 0; i < widths.length; i++) {
                    if (i > 0) {
                        divider.append("  ");
                    }
                    divider.append("-".repeat(widths[i]));
                }
                System.out.println(divider);
            }
        }
    }

    private static void printCsv(List<GraphStats> stats, boolean includeBreakdown) {
        List<String> header = new ArrayList<>(List.of(
                "file", "triples", "constraints", "node_shapes", "targeted_classes", "property_shapes"
        ));
        List<String> breakdownKeys = new ArrayList<>();
        if (includeBreakdown) {
            breakdownKeys = collectBreakdownKeys(stats);
            header.addAll(breakdownKeys);
        }
        System.out.println(String.join(",", header));

        for (GraphStats s : stats) {
            List<String> row = new ArrayList<>(List.of(
                    csvEscape(s.path().toString()),
                    Long.toString(s.triples()),
                    Long.toString(s.constraints()),
                    Long.toString(s.nodeShapes()),
                    Long.toString(s.targetedClasses()),
                    Long.toString(s.propertyShapes())
            ));
            if (includeBreakdown) {
                for (String key : breakdownKeys) {
                    row.add(Long.toString(s.constraintBreakdown().getOrDefault(key, 0L)));
                }
            }
            System.out.println(String.join(",", row));
        }
    }

    private static void printBreakdown(List<GraphStats> stats) {
        List<String> keys = collectBreakdownKeys(stats);
        if (keys.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("Constraint breakdown:");
        for (GraphStats s : stats) {
            System.out.println(s.displayPath());
            for (String key : keys) {
                long value = s.constraintBreakdown().getOrDefault(key, 0L);
                if (value > 0) {
                    System.out.printf("  %-34s %,d%n", key, value);
                }
            }
        }
    }

    private static List<String> collectBreakdownKeys(Collection<GraphStats> stats) {
        return stats.stream()
                .flatMap(s -> s.constraintBreakdown().keySet().stream())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
    }

    private static String csvEscape(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static String formatLong(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String pad(String value, int width, boolean leftAlign) {
        return leftAlign
                ? String.format(Locale.ROOT, "%-" + width + "s", value)
                : String.format(Locale.ROOT, "%" + width + "s", value);
    }

    private static void printUsage() {
        System.out.println("Usage: analyze_shape_graphs.sh [--csv] [--breakdown] <file-or-dir>...");
        System.out.println();
        System.out.println("Counts per shape graph:");
        System.out.println("  - triples");
        System.out.println("  - constraints (supported by parser/ShaclParser)");
        System.out.println("  - node_shapes (same semantics as ShaclParser.findNodeShapes())");
        System.out.println("  - targeted_classes (distinct sh:targetClass objects)");
        System.out.println("  - property_shapes (explicit sh:PropertyShape + all sh:property objects)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ./analyze_shape_graphs.sh ../Experimental_Data/LUBM_SHACL_preprocessed.ttl");
        System.out.println("  ./analyze_shape_graphs.sh --csv ../Experimental_Data");
        System.out.println("  ./analyze_shape_graphs.sh --breakdown ../Experimental_Data/Shape100_wiki_preprocessed.ttl");
    }

    private static Map<Node, String> createConstraintLabels() {
        Map<Node, String> labels = new LinkedHashMap<>();
        labels.put(ShaclVocabulary.SH_MIN_COUNT.asNode(), "sh:minCount");
        labels.put(ShaclVocabulary.SH_MAX_COUNT.asNode(), "sh:maxCount");
        labels.put(ShaclVocabulary.SH_DATATYPE.asNode(), "sh:datatype");
        labels.put(ShaclVocabulary.SH_CLASS.asNode(), "sh:class");
        labels.put(ShaclVocabulary.SH_NODE_KIND.asNode(), "sh:nodeKind");
        labels.put(ShaclVocabulary.SH_NODE_KIND_WRONG.asNode(), "sh:NodeKind");
        labels.put(ShaclVocabulary.SH_MIN_LENGTH.asNode(), "sh:minLength");
        labels.put(ShaclVocabulary.SH_MAX_LENGTH.asNode(), "sh:maxLength");
        labels.put(ShaclVocabulary.SH_PATTERN.asNode(), "sh:pattern");
        labels.put(ShaclVocabulary.SH_FLAGS.asNode(), "sh:flags");
        labels.put(ShaclVocabulary.SH_IN.asNode(), "sh:in");
        labels.put(ShaclVocabulary.SH_HAS_VALUE.asNode(), "sh:hasValue");
        labels.put(ShaclVocabulary.SH_MIN_INCLUSIVE.asNode(), "sh:minInclusive");
        labels.put(ShaclVocabulary.SH_MAX_INCLUSIVE.asNode(), "sh:maxInclusive");
        labels.put(ShaclVocabulary.SH_MIN_EXCLUSIVE.asNode(), "sh:minExclusive");
        labels.put(ShaclVocabulary.SH_MAX_EXCLUSIVE.asNode(), "sh:maxExclusive");
        labels.put(ShaclVocabulary.SH_LANGUAGE_IN.asNode(), "sh:languageIn");
        labels.put(ShaclVocabulary.SH_UNIQUE_LANG.asNode(), "sh:uniqueLang");
        labels.put(ShaclVocabulary.SH_NOT.asNode(), "sh:not");
        labels.put(ShaclVocabulary.SH_AND.asNode(), "sh:and");
        labels.put(ShaclVocabulary.SH_OR.asNode(), "sh:or");
        labels.put(ShaclVocabulary.SH_XONE.asNode(), "sh:xone");
        labels.put(ShaclVocabulary.SH_NODE.asNode(), "sh:node");
        labels.put(ShaclVocabulary.SH_EQUALS.asNode(), "sh:equals");
        labels.put(ShaclVocabulary.SH_DISJOINT.asNode(), "sh:disjoint");
        labels.put(ShaclVocabulary.SH_LESS_THAN.asNode(), "sh:lessThan");
        labels.put(ShaclVocabulary.SH_LESS_THAN_OR_EQUALS.asNode(), "sh:lessThanOrEquals");
        labels.put(ShaclVocabulary.SH_QUALIFIED_VALUE_SHAPE.asNode(), "sh:qualifiedValueShape");
        labels.put(ShaclVocabulary.SH_QUALIFIED_MIN_COUNT.asNode(), "sh:qualifiedMinCount");
        labels.put(ShaclVocabulary.SH_QUALIFIED_MAX_COUNT.asNode(), "sh:qualifiedMaxCount");
        labels.put(ShaclVocabulary.SH_QUALIFIED_VALUE_SHAPES_DISJOINT.asNode(), "sh:qualifiedValueShapesDisjoint");
        labels.put(ShaclVocabulary.SH_CLOSED.asNode(), "sh:closed");
        labels.put(ShaclVocabulary.SH_IGNORED_PROPERTIES.asNode(), "sh:ignoredProperties");
        labels.put(ShaclVocabulary.SH_PATH.asNode(), "sh:path");
        return labels;
    }

    private record GraphStats(
            Path path,
            String displayPath,
            long triples,
            long constraints,
            long nodeShapes,
            long targetedClasses,
            long propertyShapes,
            Map<String, Long> constraintBreakdown
    ) {}

    private static final class CountingStream extends StreamRDFBase {
        private final Path path;
        private long triples;
        private long constraints;
        private final Set<String> nodeShapes = new LinkedHashSet<>();
        private final Set<String> targetedClasses = new LinkedHashSet<>();
        private final Set<String> propertyShapes = new LinkedHashSet<>();
        private final Map<String, Long> breakdown = new LinkedHashMap<>();

        private CountingStream(Path path) {
            this.path = path;
        }

        @Override
        public void triple(Triple triple) {
            accept(triple.getSubject(), triple.getPredicate(), triple.getObject());
        }

        @Override
        public void quad(Quad quad) {
            accept(quad.getSubject(), quad.getPredicate(), quad.getObject());
        }

        private void accept(Node subject, Node predicate, Node object) {
            triples++;

            if (predicate.equals(RDF_TYPE)) {
                if (object.equals(SH_NODE_SHAPE) && isResourceNode(subject)) {
                    nodeShapes.add(nodeKey(subject));
                } else if (object.equals(SH_PROPERTY_SHAPE) && isResourceNode(subject)) {
                    propertyShapes.add(nodeKey(subject));
                }
            }

            if (predicate.equals(SH_TARGET_CLASS)) {
                if (subject.isURI()) {
                    nodeShapes.add(nodeKey(subject));
                }
                if (isResourceNode(object)) {
                    targetedClasses.add(nodeKey(object));
                }
            } else if (predicate.equals(SH_TARGET_NODE)
                    || predicate.equals(SH_TARGET_SUBJECTS_OF)
                    || predicate.equals(SH_TARGET_OBJECTS_OF)) {
                if (subject.isURI()) {
                    nodeShapes.add(nodeKey(subject));
                }
            } else if (predicate.equals(SH_PROPERTY) && isResourceNode(object)) {
                propertyShapes.add(nodeKey(object));
            }

            String label = CONSTRAINT_LABELS.get(predicate);
            if (label != null) {
                constraints++;
                breakdown.merge(label, 1L, Long::sum);
            }
        }

        private GraphStats toStats() {
            Path cwd = Paths.get("").toAbsolutePath().normalize();
            String displayPath;
            try {
                displayPath = cwd.relativize(path).toString();
            } catch (IllegalArgumentException e) {
                displayPath = path.toString();
            }
            return new GraphStats(
                    path,
                    displayPath,
                    triples,
                    constraints,
                    nodeShapes.size(),
                    targetedClasses.size(),
                    propertyShapes.size(),
                    Map.copyOf(breakdown)
            );
        }

        private static boolean isResourceNode(Node node) {
            return node != null && (node.isURI() || node.isBlank());
        }

        private static String nodeKey(Node node) {
            if (node.isURI()) {
                return node.getURI();
            }
            if (node.isBlank()) {
                return "_:" + node.getBlankNodeLabel();
            }
            return node.toString();
        }
    }

    private static final class Options {
        private final boolean csv;
        private final boolean breakdown;
        private final boolean showHelp;
        private final List<String> inputs;

        private Options(boolean csv, boolean breakdown, boolean showHelp, List<String> inputs) {
            this.csv = csv;
            this.breakdown = breakdown;
            this.showHelp = showHelp;
            this.inputs = inputs;
        }

        private static Options parse(String[] args) {
            boolean csv = false;
            boolean breakdown = false;
            boolean help = false;
            List<String> inputs = new ArrayList<>();

            for (String arg : args) {
                switch (arg) {
                    case "--csv" -> csv = true;
                    case "--breakdown" -> breakdown = true;
                    case "-h", "--help" -> help = true;
                    default -> inputs.add(arg);
                }
            }

            return new Options(csv, breakdown, help, inputs);
        }
    }
}
