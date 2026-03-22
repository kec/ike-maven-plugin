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

/**
 * Inject a "back to project site" breadcrumb link into JaCoCo HTML reports.
 *
 * <p>Finds all HTML files in the target directory and replaces the
 * JaCoCo breadcrumb {@code <div>} with the same div plus a navigation
 * link prepended. This allows users viewing JaCoCo reports to navigate
 * back to the project site.
 *
 * <p>Replaces: inline {@code sh -c sed} execution for JaCoCo breadcrumb
 * injection in the reactor POM.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:inject-breadcrumb -DtargetDir=target/site/jacoco
 * </pre>
 */
@Mojo(name = "inject-breadcrumb",
      defaultPhase = LifecyclePhase.VERIFY,
      threadSafe = true)
public class InjectBreadcrumbMojo extends AbstractMojo {

    /** Directory containing JaCoCo HTML reports. */
    @Parameter(property = "targetDir",
               defaultValue = "${project.build.directory}/site/jacoco")
    File targetDir;

    /** Relative URL for the breadcrumb link. */
    @Parameter(property = "breadcrumb.link", defaultValue = "../index.html")
    String link;

    /** Display label for the breadcrumb link. */
    @Parameter(property = "breadcrumb.label", defaultValue = "\u2190 Project Site")
    String label;

    @Override
    public void execute() throws MojoExecutionException {
        if (!targetDir.isDirectory()) {
            getLog().info("inject-breadcrumb: directory does not exist, "
                    + "skipping — " + targetDir);
            return;
        }

        int patched = 0;
        try {
            patched = processDirectory(targetDir.toPath(), link, label);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to inject breadcrumbs in " + targetDir, e);
        }

        if (patched > 0) {
            getLog().info("inject-breadcrumb: patched " + patched
                    + " HTML file(s) in " + targetDir);
        } else {
            getLog().info("inject-breadcrumb: no breadcrumb divs found in "
                    + targetDir);
        }
    }

    /**
     * Recursively process all HTML files in a directory tree.
     *
     * @return number of files that were modified
     */
    private int processDirectory(Path dir, String breadcrumbLink,
                                 String breadcrumbLabel) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    count += processDirectory(entry, breadcrumbLink,
                            breadcrumbLabel);
                } else if (entry.toString().endsWith(".html")) {
                    String html = Files.readString(entry);
                    String patched = injectBreadcrumb(html, breadcrumbLink,
                            breadcrumbLabel);
                    if (!html.equals(patched)) {
                        Files.writeString(entry, patched);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Inject a breadcrumb navigation link into JaCoCo's breadcrumb div.
     *
     * <p>Replaces {@code <div class="breadcrumb" id="breadcrumb">} with
     * the same div plus a styled link and separator prepended.
     *
     * @param html  the HTML content
     * @param link  relative URL for the breadcrumb link
     * @param label display label for the link
     * @return HTML with the breadcrumb injected, or unchanged if the
     *         breadcrumb div is not present
     */
    public static String injectBreadcrumb(String html, String link,
                                          String label) {
        return html.replace(
                "<div class=\"breadcrumb\" id=\"breadcrumb\">",
                "<div class=\"breadcrumb\" id=\"breadcrumb\">"
                        + "<a href=\"" + link
                        + "\" style=\"font-weight:bold;margin-right:8px\">"
                        + label + "</a> | ");
    }
}
