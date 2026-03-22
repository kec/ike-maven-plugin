package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scan renderer log files for error patterns and print a summary.
 *
 * <p>Finds all {@code renderer-*.log} files in the configured directory,
 * counts lines matching common error patterns (error, fatal, exception,
 * failed, not found), and prints a one-line summary per renderer.
 *
 * <p>Never fails the build — renderer exit codes already fail the build
 * if appropriate. This goal is informational only.
 *
 * <p>Replaces: {@code scan-renderer-logs.sh}
 *
 * <p>Usage:
 * <pre>
 * mvn ike:scan-logs -DlogsDir=target/generated-docs
 * </pre>
 */
@Mojo(name = "scan-logs",
      defaultPhase = LifecyclePhase.VERIFY,
      threadSafe = true)
public class ScanRendererLogsMojo extends AbstractMojo {

    /** Directory containing {@code renderer-*.log} files. */
    @Parameter(property = "logsDir", required = true)
    File logsDir;

    /** Pattern matching error indicators in renderer output. */
    static final Pattern ERROR_PATTERN = Pattern.compile(
            "error|fatal|exception|failed|not found",
            Pattern.CASE_INSENSITIVE);

    @Override
    public void execute() throws MojoExecutionException {
        if (!logsDir.isDirectory()) {
            getLog().debug("scan-logs: directory does not exist, skipping — "
                    + logsDir);
            return;
        }

        List<Path> logFiles = collectLogFiles(logsDir.toPath());
        if (logFiles.isEmpty()) {
            getLog().debug("scan-logs: no renderer-*.log files in " + logsDir);
            return;
        }

        getLog().info("");
        getLog().info("── Renderer Log Summary ──────────────────────────────────────");

        for (Path logFile : logFiles) {
            try {
                String content = Files.readString(logFile);
                String name = extractRendererName(logFile.getFileName().toString());
                long lines = content.lines().count();
                int errors = countErrors(content);

                getLog().info(formatSummary(name, (int) lines, errors)
                        + (errors > 0 ? " — see " + logFile : ""));
            } catch (IOException e) {
                getLog().warn("scan-logs: could not read " + logFile + ": " + e.getMessage());
            }
        }
        getLog().info("");
    }

    /**
     * Collect and sort {@code renderer-*.log} files from a directory.
     */
    private static List<Path> collectLogFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, "renderer-*.log")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException e) {
            // Directory unreadable — return empty list
        }
        Collections.sort(files);
        return files;
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Count lines containing error patterns in log content.
     *
     * <p>Matches case-insensitively against: error, fatal, exception,
     * failed, not found.
     *
     * @param logContent the log file content
     * @return number of lines containing at least one error pattern
     */
    public static int countErrors(String logContent) {
        int count = 0;
        for (String line : logContent.split("\n", -1)) {
            Matcher m = ERROR_PATTERN.matcher(line);
            if (m.find()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Format a one-line summary for a renderer log.
     *
     * @param rendererName short renderer name (e.g., "prince", "fop")
     * @param lines        total lines in the log file
     * @param errors       number of error lines
     * @return formatted summary string
     */
    public static String formatSummary(String rendererName, int lines, int errors) {
        if (errors > 0) {
            return "  [" + rendererName + "] " + errors + " error(s)";
        }
        return "  [" + rendererName + "] OK (" + lines + " lines)";
    }

    /**
     * Extract the renderer name from a log filename.
     *
     * <p>Given {@code "renderer-prince.log"}, returns {@code "prince"}.
     *
     * @param filename the log filename (without path)
     * @return the renderer name
     */
    public static String extractRendererName(String filename) {
        // Strip "renderer-" prefix and ".log" suffix
        String name = filename;
        if (name.startsWith("renderer-")) {
            name = name.substring("renderer-".length());
        }
        if (name.endsWith(".log")) {
            name = name.substring(0, name.length() - ".log".length());
        }
        return name;
    }
}
