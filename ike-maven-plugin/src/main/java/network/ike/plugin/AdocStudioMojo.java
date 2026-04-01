package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generate Adoc Studio sidecar projects for assembly modules.
 *
 * <p>Extracts a bundled Swift script and runs it against the current
 * project directory. For each assembly module (directory with a
 * {@code pom.xml} and {@code src/docs/asciidoc/}), the script creates
 * an {@code .adocproject} file in the sidecar directory. Each project's
 * anchor folder contains a macOS NSURL bookmark pointing back into the
 * Maven source tree, so edits in Adoc Studio land on the canonical
 * sources.
 *
 * <p>The sidecar directory defaults to
 * {@code ~/Documents/ike-adoc-studio/} and stays outside the
 * Syncthing/git tree. Each machine generates its own bookmarks.
 *
 * <p>Prerequisite: run {@code mvn validate} first to unpack topic
 * dependencies into {@code target/generated-sources/asciidoc/} so
 * Adoc Studio can resolve includes.
 *
 * <p>Usage:
 * <pre>
 *   mvnw ike:adocstudio                          # default sidecar
 *   mvnw ike:adocstudio -Dadocstudio.outputDir=~/my-adoc-projects
 * </pre>
 *
 * <p>macOS only — the Swift runtime is required for NSURL bookmark
 * generation. On non-macOS platforms, the goal logs a warning and
 * exits cleanly.
 */
@Mojo(name = "adocstudio", requiresProject = false, threadSafe = true)
public class AdocStudioMojo extends AbstractMojo {

    private static final String SWIFT_RESOURCE =
            "/adocstudio/bootstrap-adocprojects.swift";

    /**
     * Root directory containing assembly modules. Defaults to the
     * current working directory (typically {@code ike-lab-documents/}).
     */
    @Parameter(property = "adocstudio.sourceDir",
               defaultValue = "${user.dir}")
    private String sourceDir;

    /**
     * Sidecar output directory for generated {@code .adocproject}
     * files. Each assembly gets a subdirectory here.
     */
    @Parameter(property = "adocstudio.outputDir",
               defaultValue = "${user.home}/Documents/ike-adoc-studio")
    private String outputDir;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE Adoc Studio — Sidecar Generator");
        getLog().info("══════════════════════════════════════════════════════════════");

        // ── Platform guard ───────────────────────────────────
        if (!isMacOS()) {
            getLog().warn("  Adoc Studio sidecar generation requires macOS.");
            getLog().warn("  NSURL bookmarks cannot be created on this platform.");
            getLog().info("");
            return;
        }

        // ── Verify Swift is available ────────────────────────
        if (!isSwiftAvailable()) {
            throw new MojoExecutionException(
                    "Swift runtime not found. Install Xcode or "
                    + "Command Line Tools: xcode-select --install");
        }

        // ── Extract Swift script to temp file ────────────────
        Path scriptFile;
        try {
            scriptFile = extractScript();
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to extract Swift script from plugin JAR", e);
        }

        // ── Resolve paths ────────────────────────────────────
        Path source = Path.of(sourceDir).toAbsolutePath().normalize();
        Path output = Path.of(resolveHome(outputDir))
                          .toAbsolutePath().normalize();

        getLog().info("  Source: " + source);
        getLog().info("  Output: " + output);
        getLog().info("");

        if (!Files.isDirectory(source)) {
            throw new MojoExecutionException(
                    "Source directory does not exist: " + source);
        }

        // ── Execute Swift script ─────────────────────────────
        try {
            int exitCode = runSwift(scriptFile, source, output);
            if (exitCode != 0) {
                throw new MojoExecutionException(
                        "Swift script exited with code " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException(
                    "Failed to execute Swift script", e);
        } finally {
            // Clean up temp file
            try {
                Files.deleteIfExists(scriptFile);
            } catch (IOException ignored) {
                // Best-effort cleanup
            }
        }

        getLog().info("");
        getLog().info("  Open any project in Adoc Studio from:");
        getLog().info("    " + output);
        getLog().info("");
        getLog().info("  Tip: run 'mvn validate' first to unpack topic");
        getLog().info("  dependencies so includes resolve in the preview.");
        getLog().info("");
    }

    // ── Helpers ──────────────────────────────────────────────

    private Path extractScript() throws IOException, MojoExecutionException {
        try (InputStream is = getClass().getResourceAsStream(SWIFT_RESOURCE)) {
            if (is == null) {
                throw new MojoExecutionException(
                        "Swift script not found on classpath: "
                        + SWIFT_RESOURCE);
            }
            Path tempScript = Files.createTempFile(
                    "ike-adocstudio-", ".swift");
            Files.write(tempScript, is.readAllBytes());
            return tempScript;
        }
    }

    private int runSwift(Path scriptFile, Path source, Path output)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "swift", scriptFile.toString(),
                source.toString(),
                output.toString());
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        // Stream stdout to Maven log
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(proc.getInputStream(),
                        StandardCharsets.UTF_8))) {
            stdout.lines().forEach(line -> getLog().info(line));
        }

        // Stream stderr to Maven log as warnings
        try (BufferedReader stderr = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(),
                        StandardCharsets.UTF_8))) {
            stderr.lines().forEach(line -> getLog().warn(line));
        }

        return proc.waitFor();
    }

    private boolean isMacOS() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }

    private boolean isSwiftAvailable() {
        try {
            Process proc = new ProcessBuilder("swift", "--version")
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Expand leading {@code ~} to user home directory.
     */
    private String resolveHome(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return System.getProperty("user.home")
                    + path.substring(1);
        }
        return path;
    }
}
