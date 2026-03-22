package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Remove bare {@code <rect/>} elements from generated HTML files.
 *
 * <p>Mermaid generates {@code <rect/>} elements (no attributes) as
 * non-functional placeholders inside flowchart label groups. These
 * elements violate the SVG spec (missing required width/height) and
 * cause warnings in Prince and other PDF renderers.
 *
 * <p>Replaces: {@code fix-inline-svg.sh}
 *
 * <p>Usage:
 * <pre>
 * mvn ike:fix-svg -DhtmlFile=target/generated-docs/html/document.html
 * </pre>
 */
@Mojo(name = "fix-svg",
      defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
      threadSafe = true)
public class FixSvgMojo extends AbstractMojo {

    /** The HTML file to process. */
    @Parameter(property = "htmlFile", required = true)
    File htmlFile;

    /** Skip execution. */
    @Parameter(property = "skip", defaultValue = "false")
    boolean skip;

    /** Creates this goal instance. */
    public FixSvgMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().debug("fix-svg: skipped");
            return;
        }
        if (!htmlFile.isFile()) {
            getLog().info("fix-svg: file does not exist, skipping — " + htmlFile);
            return;
        }

        try {
            String html = Files.readString(htmlFile.toPath());
            int count = countBareRects(html);

            if (count == 0) {
                getLog().info("fix-svg: no bare <rect/> elements in " + htmlFile.getName());
                return;
            }

            String cleaned = removeBareRects(html);
            Files.writeString(htmlFile.toPath(), cleaned);
            getLog().info("fix-svg: removed " + count
                    + " bare <rect/> element(s) from " + htmlFile.getName());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to process " + htmlFile, e);
        }
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Remove all bare {@code <rect/>} elements from HTML content.
     *
     * @param html the HTML content
     * @return cleaned HTML with bare rects removed
     */
    public static String removeBareRects(String html) {
        return html.replace("<rect/>", "");
    }

    /**
     * Count occurrences of bare {@code <rect/>} elements in HTML content.
     *
     * @param html the HTML content
     * @return number of bare rect elements found
     */
    public static int countBareRects(String html) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf("<rect/>", idx)) != -1) {
            count++;
            idx += "<rect/>".length();
        }
        return count;
    }
}
