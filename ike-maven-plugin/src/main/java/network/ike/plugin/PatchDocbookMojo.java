package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Patch stock DocBook XSL stylesheets to suppress Saxon warnings.
 *
 * <p>Applies two targeted fixes to the downloaded DocBook XSL 1.79.2
 * distribution:
 * <ol>
 *   <li>{@code fo/docbook.xsl} — removes the direct include of
 *       {@code ../common/utility.xsl} which creates a diamond import
 *       (Saxon SXWN9019: included or imported more than once).</li>
 *   <li>{@code fo/math.xsl} — removes the dead
 *       {@code <xsl:variable name="output.delims">} block that is
 *       computed but never used (Saxon SXWN9001).</li>
 * </ol>
 *
 * <p>Replaces: {@code patch-docbook-xsl.sh}
 *
 * <p>Usage:
 * <pre>
 * mvn ike:patch-docbook -DdocbookDir=target/docbook-xsl
 * </pre>
 */
@Mojo(name = "patch-docbook",
      defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
      threadSafe = true)
public class PatchDocbookMojo extends AbstractMojo {

    /** Root directory of the unpacked DocBook XSL distribution. */
    @Parameter(property = "docbookDir", required = true)
    File docbookDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (!docbookDir.isDirectory()) {
            getLog().info("patch-docbook: directory does not exist, skipping — "
                    + docbookDir);
            return;
        }

        // Patch fo/docbook.xsl — remove diamond utility.xsl include
        Path docbookXsl = docbookDir.toPath().resolve("fo/docbook.xsl");
        patchFile(docbookXsl, "utility.xsl include",
                PatchDocbookMojo::removeUtilityInclude);

        // Patch fo/math.xsl — remove dead output.delims variable
        Path mathXsl = docbookDir.toPath().resolve("fo/math.xsl");
        patchFile(mathXsl, "dead output.delims variable",
                PatchDocbookMojo::removeDeadVariable);

        getLog().info("patch-docbook: patched DocBook XSL in " + docbookDir);
    }

    /**
     * Read a file, apply a transformation, and write it back.
     * Logs and skips if the file does not exist.
     */
    private void patchFile(Path file, String description,
                           java.util.function.UnaryOperator<String> transform)
            throws MojoExecutionException {
        if (!Files.isRegularFile(file)) {
            getLog().info("patch-docbook: " + file.getFileName()
                    + " not found, skipping " + description);
            return;
        }
        try {
            String content = Files.readString(file);
            String patched = transform.apply(content);
            if (!content.equals(patched)) {
                Files.writeString(file, patched);
                getLog().info("patch-docbook: removed " + description
                        + " from " + file.getFileName());
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to patch " + file, e);
        }
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Remove the {@code <xsl:include href="../common/utility.xsl"/>}
     * line from DocBook's {@code fo/docbook.xsl}.
     *
     * @param xsl the XSL content
     * @return patched XSL with the utility include removed
     */
    public static String removeUtilityInclude(String xsl) {
        return xsl.replace("<xsl:include href=\"../common/utility.xsl\"/>", "");
    }

    /**
     * Remove the dead {@code <xsl:variable name="output.delims">}
     * block from DocBook's {@code fo/math.xsl}.
     *
     * <p>The variable spans multiple lines and is matched with a
     * non-greedy pattern from the opening tag to the closing tag.
     *
     * @param xsl the XSL content
     * @return patched XSL with the dead variable removed
     */
    public static String removeDeadVariable(String xsl) {
        return xsl.replaceAll(
                "(?s)<xsl:variable name=\"output\\.delims\">.*?</xsl:variable>",
                "");
    }
}
