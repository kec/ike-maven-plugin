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
import java.util.List;

/**
 * Render PDF from intermediate files using an external renderer.
 *
 * <p>Wraps the five PDF renderers supported by the IKE documentation
 * pipeline into a single Maven goal with consistent configuration,
 * executable validation, and multi-document support.
 *
 * <p>Renderers fall into two families:
 * <ul>
 *   <li><b>CSS-based</b> ({@code prince}, {@code ah}, {@code weasyprint})
 *       — convert print-layout HTML to PDF via CSS Paged Media</li>
 *   <li><b>FO-based</b> ({@code xep}, {@code fop})
 *       — convert XSL-FO to PDF</li>
 * </ul>
 *
 * <p>By default, the goal discovers all input files in {@code inputDir}
 * and renders each one. To render specific documents, set the
 * {@code documents} parameter to a list of base names (without extension).
 *
 * <p>If the goal is skipped, it produces no log output at all — unlike
 * exec-maven-plugin which logs "skipping" for every skipped execution.
 *
 * <p>Usage in a POM:
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;prince-pdf&lt;/id&gt;
 *   &lt;phase&gt;package&lt;/phase&gt;
 *   &lt;goals&gt;&lt;goal&gt;render-pdf&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;renderer&gt;prince&lt;/renderer&gt;
 *     &lt;inputDir&gt;${asciidoc.output.directory}/pdf-html-prince&lt;/inputDir&gt;
 *     &lt;outputDir&gt;${asciidoc.output.directory}/pdf-prince&lt;/outputDir&gt;
 *     &lt;stylesheet&gt;${asciidoc.output.directory}/pdf-html-prince/ike-print.css&lt;/stylesheet&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 */
@Mojo(name = "render-pdf",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresProject = true,
      threadSafe = true)
public class RenderPdfMojo extends AbstractMojo {

    /**
     * Renderer to use. One of: {@code prince}, {@code ah},
     * {@code weasyprint}, {@code xep}, {@code fop}.
     */
    @Parameter(property = "ike.renderer", required = true)
    private String renderer;

    /**
     * Path to the renderer executable. Defaults are renderer-specific:
     * {@code prince}, {@code AHFCmd}, {@code weasyprint}, {@code java}.
     */
    @Parameter(property = "ike.renderer.executable")
    private String executable;

    /** Skip this execution entirely (no log output when skipped). */
    @Parameter(property = "ike.renderer.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Directory containing intermediate files (HTML for CSS renderers,
     * FO for FO renderers).
     */
    @Parameter(required = true)
    private File inputDir;

    /** Directory where rendered PDFs will be written. */
    @Parameter(required = true)
    private File outputDir;

    // ── CSS renderer parameters ──────────────────────────────────────

    /** Print stylesheet for CSS renderers (prince, ah, weasyprint). */
    @Parameter
    private File stylesheet;

    /**
     * PDF profile for CSS renderers.
     * Prince uses {@code PDF/UA-1}, AH uses {@code @PDF/UA-1}.
     */
    @Parameter(property = "ike.renderer.pdfProfile", defaultValue = "PDF/UA-1")
    private String pdfProfile;

    // ── FO renderer parameters ───────────────────────────────────────

    /** Configuration file for FO renderers (XEP config or FOP xconf). */
    @Parameter
    private File configFile;

    /**
     * Classpath for FO renderers that run via {@code java}.
     * Colon-separated paths to JAR files.
     */
    @Parameter
    private String classpath;

    // ── Document selection ────────────────────────────────────────────

    /**
     * Specific documents to render (base names without extension).
     * If empty, all input files in {@code inputDir} are rendered.
     */
    @Parameter
    private List<String> documents;

    /** Log file for renderer output. */
    @Parameter
    private File logFile;

    /** Creates this goal instance. */
    public RenderPdfMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            return;
        }

        RendererType type = resolveRenderer(renderer);
        String resolvedExecutable = resolveExecutable(type);
        validateExecutable(resolvedExecutable, type);

        List<Path> inputs = discoverInputFiles(type);
        if (inputs.isEmpty()) {
            getLog().info("render-pdf [" + renderer + "]: no input files in "
                    + inputDir);
            return;
        }

        try {
            Files.createDirectories(outputDir.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to create output directory: " + outputDir, e);
        }

        int rendered = 0;
        for (Path input : inputs) {
            String baseName = stripExtension(input.getFileName().toString());
            Path output = outputDir.toPath().resolve(baseName + ".pdf");

            List<String> command = buildCommand(
                    type, resolvedExecutable, input, output);

            try {
                int exitCode = invokeRenderer(command);
                if (exitCode != 0) {
                    throw new MojoExecutionException(
                            "Renderer " + renderer + " failed with exit code "
                                    + exitCode + " for " + input.getFileName());
                }
                rendered++;
            } catch (IOException | InterruptedException e) {
                throw new MojoExecutionException(
                        "Failed to invoke " + renderer + " for "
                                + input.getFileName(), e);
            }
        }

        getLog().info("render-pdf [" + renderer + "]: rendered " + rendered
                + " document(s) to " + outputDir);
    }

    // ── Renderer types ───────────────────────────────────────────────

    enum RendererType {
        /** Prince XML — CSS Paged Media. */
        PRINCE("prince", ".html"),
        /** Antenna House Formatter — CSS Paged Media. */
        AH("AHFCmd", ".html"),
        /** WeasyPrint — CSS Paged Media. */
        WEASYPRINT("weasyprint", ".html"),
        /** RenderX XEP — XSL-FO. */
        XEP("java", ".fo"),
        /** Apache FOP — XSL-FO. */
        FOP("java", ".fo");

        final String defaultExecutable;
        final String inputExtension;

        RendererType(String defaultExecutable, String inputExtension) {
            this.defaultExecutable = defaultExecutable;
            this.inputExtension = inputExtension;
        }

        boolean isCssBased() {
            return inputExtension.equals(".html");
        }
    }

    // ── Command building ─────────────────────────────────────────────

    List<String> buildCommand(RendererType type, String exe,
                              Path input, Path output) {
        return switch (type) {
            case PRINCE -> buildPrinceCommand(exe, input, output);
            case AH -> buildAhCommand(exe, input, output);
            case WEASYPRINT -> buildWeasyprintCommand(exe, input, output);
            case XEP -> buildXepCommand(exe, input, output);
            case FOP -> buildFopCommand(exe, input, output);
        };
    }

    private List<String> buildPrinceCommand(String exe,
                                            Path input, Path output) {
        var cmd = new ArrayList<String>();
        cmd.add(exe);
        cmd.add("--silent");
        cmd.add(input.toString());
        if (stylesheet != null) {
            cmd.add("--style");
            cmd.add(stylesheet.toString());
        }
        cmd.add("--output");
        cmd.add(output.toString());
        if (pdfProfile != null && !pdfProfile.isEmpty()) {
            cmd.add("--pdf-profile=" + pdfProfile);
        }
        return cmd;
    }

    private List<String> buildAhCommand(String exe,
                                        Path input, Path output) {
        var cmd = new ArrayList<String>();
        cmd.add(exe);
        cmd.add("-cssmode");
        if (stylesheet != null) {
            cmd.add("-css");
            cmd.add(stylesheet.toString());
        }
        cmd.add("-d");
        cmd.add(input.toString());
        cmd.add("-o");
        cmd.add(output.toString());
        if (pdfProfile != null && !pdfProfile.isEmpty()) {
            cmd.add("-p");
            cmd.add("@" + pdfProfile);
        }
        return cmd;
    }

    private List<String> buildWeasyprintCommand(String exe,
                                                Path input, Path output) {
        var cmd = new ArrayList<String>();
        cmd.add(exe);
        cmd.add(input.toString());
        cmd.add(output.toString());
        if (stylesheet != null) {
            cmd.add("--stylesheet");
            cmd.add(stylesheet.toString());
        }
        return cmd;
    }

    private List<String> buildXepCommand(String exe,
                                         Path input, Path output) {
        var cmd = new ArrayList<String>();
        cmd.add(exe);
        if (classpath != null && !classpath.isEmpty()) {
            cmd.add("-classpath");
            cmd.add(classpath);
        }
        if (configFile != null) {
            cmd.add("-Dcom.renderx.xep.CONFIG=" + configFile);
        }
        cmd.add("com.renderx.xep.XSLDriver");
        cmd.add("-fo");
        cmd.add(input.toString());
        cmd.add("-pdf");
        cmd.add(output.toString());
        return cmd;
    }

    private List<String> buildFopCommand(String exe,
                                         Path input, Path output) {
        var cmd = new ArrayList<String>();
        cmd.add(exe);
        if (classpath != null && !classpath.isEmpty()) {
            cmd.add("-classpath");
            cmd.add(classpath);
        }
        cmd.add("org.apache.fop.cli.Main");
        cmd.add("-r");
        if (configFile != null) {
            cmd.add("-c");
            cmd.add(configFile.toString());
        }
        cmd.add("-fo");
        cmd.add(input.toString());
        cmd.add("-pdf");
        cmd.add(output.toString());
        return cmd;
    }

    // ── Process invocation ───────────────────────────────────────────

    /**
     * Invoke the renderer as an external process.
     *
     * @param command the full command line
     * @return process exit code
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the process is interrupted
     */
    int invokeRenderer(List<String> command)
            throws IOException, InterruptedException {
        getLog().debug("render-pdf: " + String.join(" ", command));

        var builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        if (logFile != null) {
            Files.createDirectories(logFile.toPath().getParent());
            builder.redirectOutput(
                    ProcessBuilder.Redirect.appendTo(logFile));
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        Process process = builder.start();
        return process.waitFor();
    }

    // ── Document discovery ───────────────────────────────────────────

    /**
     * Discover input files to render.
     *
     * <p>If {@code documents} is set, only those named files are returned.
     * Otherwise, all files in {@code inputDir} matching the renderer's
     * input extension are returned.
     *
     * @param type the renderer type (determines input extension)
     * @return list of input file paths
     * @throws MojoExecutionException if inputDir is not a directory
     */
    List<Path> discoverInputFiles(RendererType type)
            throws MojoExecutionException {
        if (!inputDir.isDirectory()) {
            return List.of();
        }

        String ext = type.inputExtension;
        var result = new ArrayList<Path>();

        if (documents != null && !documents.isEmpty()) {
            // Explicit document list
            for (String doc : documents) {
                Path file = inputDir.toPath().resolve(doc + ext);
                if (Files.isRegularFile(file)) {
                    result.add(file);
                } else {
                    getLog().warn("render-pdf [" + renderer
                            + "]: document not found — " + file.getFileName());
                }
            }
        } else {
            // Auto-discover all input files
            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(inputDir.toPath(),
                                 "*" + ext)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        result.add(file);
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to scan input directory: " + inputDir, e);
            }
        }

        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    static RendererType resolveRenderer(String name)
            throws MojoExecutionException {
        return switch (name.toLowerCase()) {
            case "prince" -> RendererType.PRINCE;
            case "ah", "antennahouse" -> RendererType.AH;
            case "weasyprint" -> RendererType.WEASYPRINT;
            case "xep" -> RendererType.XEP;
            case "fop" -> RendererType.FOP;
            default -> throw new MojoExecutionException(
                    "Unknown renderer: " + name
                            + ". Supported: prince, ah, weasyprint, xep, fop");
        };
    }

    private String resolveExecutable(RendererType type) {
        return (executable != null && !executable.isEmpty())
                ? executable
                : type.defaultExecutable;
    }

    private void validateExecutable(String exe, RendererType type)
            throws MojoExecutionException {
        // For java-based renderers (xep, fop), validate classpath instead
        if (type == RendererType.XEP || type == RendererType.FOP) {
            if (classpath == null || classpath.isEmpty()) {
                throw new MojoExecutionException(
                        "Renderer " + renderer
                                + " requires <classpath> to be set");
            }
            return;
        }

        // For external executables, check if on PATH
        try {
            var check = new ProcessBuilder("which", exe);
            check.redirectErrorStream(true);
            check.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            int result = check.start().waitFor();
            if (result != 0) {
                throw new MojoExecutionException(
                        "Renderer executable not found: " + exe
                                + ". Install it or set <executable>.");
            }
        } catch (IOException | InterruptedException e) {
            getLog().warn("Could not validate executable '" + exe
                    + "': " + e.getMessage());
        }
    }

    static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }
}
