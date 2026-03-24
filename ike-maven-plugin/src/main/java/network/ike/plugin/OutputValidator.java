package network.ike.plugin;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans generated AsciiDoc output for common conversion problems.
 *
 * <p>Detects unresolved attributes, missing includes, broken cross-references,
 * and missing images. Results are returned as a list of {@link Issue} records
 * for the caller to log or fail the build.
 */
public class OutputValidator {

    /** Unresolved attribute reference: {some-attribute} in output text. */
    private static final Pattern UNRESOLVED_ATTR =
            Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9_-]*\\}");

    /** AsciidoctorJ's marker for a missing include directive. */
    private static final String UNRESOLVED_INCLUDE = "Unresolved directive in";

    /** Broken cross-reference marker. */
    private static final String BROKEN_XREF = "[broken]";

    /** HTML image tag — group 1 captures the src path. */
    private static final Pattern IMG_SRC =
            Pattern.compile("<img[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Severity levels for validation issues. */
    public enum Severity { WARNING, ERROR }

    /**
     * A single validation issue found in a generated file.
     *
     * @param file     the file containing the issue
     * @param line     the 1-based line number
     * @param severity WARNING or ERROR
     * @param message  human-readable description
     */
    public record Issue(Path file, int line, Severity severity, String message) {

        @Override
        public String toString() {
            return "[%s] %s:%d — %s".formatted(severity, file.getFileName(), line, message);
        }
    }

    /**
     * Validate all files in the given output directory.
     *
     * @param outputDir the directory to scan
     * @param backend   the backend that produced the output
     * @return list of issues found (empty if clean)
     * @throws IOException if directory traversal fails
     */
    public List<Issue> validate(Path outputDir, Backend backend) throws IOException {
        if (!Files.isDirectory(outputDir)) {
            return List.of();
        }

        List<Issue> issues = new ArrayList<>();
        String extension = switch (backend) {
            case HTML -> "*.html";
            case DOCBOOK -> "*.xml";
            case PDF -> null; // binary — skip
        };

        if (extension == null) {
            return issues;
        }

        PathMatcher matcher = outputDir.getFileSystem().getPathMatcher("glob:" + extension);

        Files.walkFileTree(outputDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (matcher.matches(file.getFileName())) {
                    validateFile(file, backend, issues);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return issues;
    }

    private void validateFile(Path file, Backend backend, List<Issue> issues) throws IOException {
        List<String> lines = Files.readAllLines(file);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNum = i + 1;

            // Unresolved include directives
            if (line.contains(UNRESOLVED_INCLUDE)) {
                issues.add(new Issue(file, lineNum, Severity.ERROR,
                        "Unresolved include directive"));
            }

            // Broken cross-references
            if (line.contains(BROKEN_XREF)) {
                issues.add(new Issue(file, lineNum, Severity.ERROR,
                        "Broken cross-reference"));
            }

            // Unresolved attribute references
            Matcher attrMatcher = UNRESOLVED_ATTR.matcher(line);
            while (attrMatcher.find()) {
                String attr = attrMatcher.group();
                // Skip common false positives (CSS, JS, URLs)
                if (!isFalsePositive(attr, line)) {
                    issues.add(new Issue(file, lineNum, Severity.ERROR,
                            "Unresolved attribute: " + attr));
                }
            }

            // Missing images (HTML only)
            if (backend == Backend.HTML) {
                Matcher imgMatcher = IMG_SRC.matcher(line);
                while (imgMatcher.find()) {
                    String src = imgMatcher.group(1);
                    if (!src.startsWith("data:") && !src.startsWith("http")) {
                        Path imgPath = file.getParent().resolve(src);
                        if (!Files.exists(imgPath)) {
                            issues.add(new Issue(file, lineNum, Severity.WARNING,
                                    "Missing image: " + src));
                        }
                    }
                }
            }
        }
    }

    /**
     * Filter out common false positives for unresolved attributes.
     * Code blocks, CSS, JavaScript, and URL templates all use curly
     * braces legitimately.
     */
    private boolean isFalsePositive(String attr, String line) {
        // Inside <code>, <pre>, or <listing> elements
        if (line.contains("<code") || line.contains("</code>")
                || line.contains("<pre") || line.contains("</pre>")
                || line.contains("CodeRay") || line.contains("highlight")) return true;
        // AsciiDoc source examples (include:: directives shown as text)
        if (line.contains("include::")) return true;
        // CSS: var(--foo) or content: "{bar}"
        if (line.contains("var(--") || line.contains("content:")) return true;
        // JavaScript object literals
        if (line.trim().startsWith("//") || line.trim().startsWith("*")) return true;
        // Inside <script> or <style> blocks (heuristic)
        if (line.contains("<script") || line.contains("<style")) return true;
        // URL templates
        if (line.contains("://")) return true;
        // Revision/metadata lines in AsciiDoc source listings
        if (line.contains(":revnumber:") || line.contains(":revdate:")
                || line.contains(":docdate")) return true;
        // Known AsciiDoc/HTML entities that look like attributes
        String inner = attr.substring(1, attr.length() - 1);
        if (inner.contains(".") || inner.contains("/")) return true;
        // Java/shell identifiers commonly seen in code samples
        if (inner.equals("static") || inner.equals("return") || inner.equals("this")
                || inner.equals("super") || inner.equals("new") || inner.equals("class")
                || inner.equals("void") || inner.equals("null") || inner.equals("true")
                || inner.equals("false")) return true;
        // All-uppercase identifiers are likely shell variables or symbolic constants
        if (inner.equals(inner.toUpperCase()) && inner.matches("[A-Z_]+")) return true;
        // Greek/Unicode symbol names used in scientific text
        if (inner.equals("micro") || inner.equals("mu") || inner.equals("alpha")
                || inner.equals("beta") || inner.equals("gamma") || inner.equals("delta")
                || inner.equals("XOR") || inner.equals("AND") || inner.equals("OR")) return true;
        return false;
    }
}
