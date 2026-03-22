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
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Copy rendered HTML docs and assets into the Maven site output directory.
 *
 * <p>Copies HTML files plus supporting assets (SVG diagrams, PNG/JPG
 * images, CSS stylesheets) from the Asciidoctor generated-docs directory
 * into {@code target/site/docs/} so they are deployed alongside the
 * Maven site.
 *
 * <p>Replaces: {@code copy-docs-to-site.sh}
 *
 * <p>Usage:
 * <pre>
 * mvn ike:copy-docs -DgeneratedDocsDir=target/generated-docs/html -DsiteDir=target/site
 * </pre>
 */
@Mojo(name = "copy-docs",
      defaultPhase = LifecyclePhase.SITE,
      threadSafe = true)
public class CopyDocsToSiteMojo extends AbstractMojo {

    /** Directory containing rendered HTML and assets. */
    @Parameter(property = "generatedDocsDir", required = true)
    File generatedDocsDir;

    /** Maven site output directory (e.g., {@code target/site}). */
    @Parameter(property = "siteDir", required = true)
    File siteDir;

    /** File extensions to copy from generated-docs to the site. */
    public static final List<String> COPY_EXTENSIONS =
            List.of(".html", ".svg", ".png", ".jpg", ".css");

    @Override
    public void execute() throws MojoExecutionException {
        if (!generatedDocsDir.isDirectory()) {
            getLog().info("copy-docs: generated-docs directory does not exist, "
                    + "skipping — " + generatedDocsDir);
            return;
        }

        Path source = generatedDocsDir.toPath();
        Path dest = siteDir.toPath().resolve("docs");

        int copied = 0;
        try {
            Files.createDirectories(dest);

            for (String ext : COPY_EXTENSIONS) {
                copied += copyByExtension(source, dest, ext);
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to copy docs to site", e);
        }

        if (copied > 0) {
            getLog().info("copy-docs: copied " + copied
                    + " file(s) to " + dest);
        } else {
            getLog().info("copy-docs: no matching files in "
                    + generatedDocsDir);
        }
    }

    /**
     * Copy all files with the given extension from source to dest.
     *
     * @return number of files copied
     */
    private int copyByExtension(Path source, Path dest, String extension)
            throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(source, "*" + extension)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, dest.resolve(file.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
        }
        return count;
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Check whether a filename has an extension in the copy list.
     *
     * @param filename the filename to check
     * @return true if the file should be copied
     */
    public static boolean shouldCopy(String filename) {
        String lower = filename.toLowerCase();
        return COPY_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
