import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.sparql.core.Quad;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fast RDF data-graph analyzer based on Jena's streaming parser.
 *
 * Counts:
 * - triples/statements
 * - distinct subjects
 * - distinct predicates
 * - distinct objects
 */
public final class DataGraphAnalyzer {
    private static final Set<String> RDF_EXTENSIONS = Set.of(
            ".ttl", ".nt", ".n3", ".rdf", ".xml", ".jsonld", ".trig", ".nq"
    );

    private DataGraphAnalyzer() {}

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        if (options.showHelp || options.inputs.isEmpty()) {
            printUsage();
            System.exit(options.showHelp ? 0 : 1);
        }

        List<Path> files = expandInputs(options.inputs);
        if (files.isEmpty()) {
            System.err.println("No RDF data graph files found.");
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
            printCsv(stats);
        } else {
            printTable(stats);
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
                            .filter(DataGraphAnalyzer::isRdfFile)
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
        rows.add(new String[] {"file", "triples", "subjects", "predicates", "objects"});
        for (GraphStats s : stats) {
            rows.add(new String[] {
                    s.displayPath(),
                    formatLong(s.triples()),
                    formatLong(s.subjects()),
                    formatLong(s.predicates()),
                    formatLong(s.objects())
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
                if (i > 0) {
                    line.append("  ");
                }
                boolean leftAlign = i == 0;
                line.append(leftAlign
                        ? String.format(Locale.ROOT, "%-" + widths[i] + "s", row[i])
                        : String.format(Locale.ROOT, "%" + widths[i] + "s", row[i]));
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

    private static void printCsv(List<GraphStats> stats) {
        System.out.println("file,triples,subjects,predicates,objects");
        for (GraphStats s : stats) {
            System.out.printf(
                    "\"%s\",%d,%d,%d,%d%n",
                    s.path().toString().replace("\"", "\"\""),
                    s.triples(), s.subjects(), s.predicates(), s.objects()
            );
        }
    }

    private static String formatLong(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static void printUsage() {
        System.out.println("Usage: analyze_data_graphs.sh [--csv] <file-or-dir>...");
        System.out.println();
        System.out.println("Counts per data graph:");
        System.out.println("  - triples");
        System.out.println("  - distinct subjects");
        System.out.println("  - distinct predicates");
        System.out.println("  - distinct objects");
    }

    private record GraphStats(
            Path path,
            String displayPath,
            long triples,
            long subjects,
            long predicates,
            long objects
    ) {}

    private static final class CountingStream extends StreamRDFBase {
        private final Path path;
        private long triples;
        private final Set<String> subjects = new LinkedHashSet<>();
        private final Set<String> predicates = new LinkedHashSet<>();
        private final Set<String> objects = new LinkedHashSet<>();

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
            subjects.add(nodeKey(subject));
            predicates.add(nodeKey(predicate));
            objects.add(nodeKey(object));
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
                    subjects.size(),
                    predicates.size(),
                    objects.size()
            );
        }

        private static String nodeKey(Node node) {
            if (node.isURI()) {
                return node.getURI();
            }
            if (node.isBlank()) {
                return "_:" + node.getBlankNodeLabel();
            }
            if (node.isLiteral()) {
                return node.getLiteralLexicalForm()
                        + "@@" + String.valueOf(node.getLiteralDatatypeURI())
                        + "@@" + node.getLiteralLanguage();
            }
            return node.toString();
        }
    }

    private static final class Options {
        private final boolean csv;
        private final boolean showHelp;
        private final List<String> inputs;

        private Options(boolean csv, boolean showHelp, List<String> inputs) {
            this.csv = csv;
            this.showHelp = showHelp;
            this.inputs = inputs;
        }

        private static Options parse(String[] args) {
            boolean csv = false;
            boolean help = false;
            List<String> inputs = new ArrayList<>();

            for (String arg : args) {
                switch (arg) {
                    case "--csv" -> csv = true;
                    case "-h", "--help" -> help = true;
                    default -> inputs.add(arg);
                }
            }
            return new Options(csv, help, inputs);
        }
    }
}
