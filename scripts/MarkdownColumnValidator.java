import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Validates the documented Markdown prose width without a non-JVM formatter. */
public final class MarkdownColumnValidator {
    private static final int MAX_COLUMNS = 100;
    private static final Pattern LINK_DEFINITION =
            Pattern.compile("^\\s{0,3}\\[[^]]+]:\\s+\\S+.*$");

    private MarkdownColumnValidator() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> isDocument(root, path))
                    .forEach(path -> check(root, path, violations));
        }
        if (!violations.isEmpty()) {
            violations.forEach(System.err::println);
            throw new IllegalStateException(
                    "Markdown contains lines over " + MAX_COLUMNS + " columns");
        }
    }

    private static boolean isDocument(Path root, Path path) {
        String value = root.relativize(path).toString().replace('\\', '/');
        return value.endsWith(".md")
                && (value.lastIndexOf('/') == -1 || value.startsWith(".wiki/"));
    }

    private static void check(Path root, Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            boolean fenced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                    fenced = !fenced;
                } else if (!fenced && line.length() > MAX_COLUMNS && !isException(line)) {
                    violations.add(
                            root.relativize(path)
                                    + ":"
                                    + (i + 1)
                                    + ": line is "
                                    + line.length()
                                    + " columns (maximum "
                                    + MAX_COLUMNS
                                    + ")");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to read " + path, e);
        }
    }

    private static boolean isException(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|")
                || trimmed.endsWith("|")
                || LINK_DEFINITION.matcher(line).matches()
                || line.contains("http://")
                || line.contains("https://");
    }
}
