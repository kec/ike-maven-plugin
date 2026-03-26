package network.ike.plugin;

import network.ike.docs.koncept.KonceptGlossaryProcessor;
import network.ike.docs.koncept.KonceptInlineMacro;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugin.logging.Log;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;
import org.asciidoctor.log.LogHandler;
import org.asciidoctor.log.LogRecord;
import org.asciidoctor.log.Severity;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Generate documentation from AsciiDoc sources using AsciidoctorJ.
 *
 * <p>Replaces asciidoctor-maven-plugin with a single goal that handles
 * multiple backends in one execution. Extensions (Koncept inline macro,
 * glossary postprocessor) are registered programmatically with
 * backend-aware safety — the Prawn PDF backend does not receive
 * postprocessor extensions that would crash JRuby.
 *
 * <p>Output validation is built in: unresolved attributes, missing
 * includes, broken cross-references, and missing images are detected
 * and optionally fail the build.
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>generate-docs</id>
 *     <goals><goal>asciidoc</goal></goals>
 *     <configuration>
 *         <skipHtml>false</skipHtml>
 *         <skipPrawn>false</skipPrawn>
 *         <validate>true</validate>
 *     </configuration>
 * </execution>
 * }</pre>
 */
@Mojo(name = "asciidoc",
      defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
      threadSafe = true)
public class AsciidocMojo extends AbstractMojo {

    // ── Source / output ───────────────────────────────────────────────

    /** AsciiDoc source directory. */
    @Parameter(property = "ike.asciidoc.sourceDirectory",
               defaultValue = "${project.basedir}/src/docs/asciidoc")
    File sourceDirectory;

    /** Root output directory. Backends write to subdirectories. */
    @Parameter(property = "ike.asciidoc.outputDirectory",
               defaultValue = "${project.build.directory}/ike-doc")
    File outputDirectory;

    /**
     * Source document name (e.g., {@code index.adoc}).
     * When set, only this file is converted. When null, all .adoc files
     * in the source directory are converted.
     */
    @Parameter(property = "ike.asciidoc.sourceDocumentName")
    String sourceDocumentName;

    /** Base directory for relative includes. */
    @Parameter(property = "ike.asciidoc.baseDir",
               defaultValue = "${project.basedir}/src/docs/asciidoc")
    File baseDir;

    // ── Backend skip flags ────────────────────────────────────────────

    /** Skip all execution. */
    @Parameter(property = "ike.asciidoc.skip", defaultValue = "false")
    boolean skip;

    /** Skip HTML5 backend. */
    @Parameter(property = "ike.skip.html", defaultValue = "false")
    boolean skipHtml;

    /** Skip single-file HTML generation. */
    @Parameter(property = "ike.skip.html-single", defaultValue = "true")
    boolean skipHtmlSingle;

    /** Skip Prawn PDF backend. */
    @Parameter(property = "ike.skip.prawn", defaultValue = "true")
    boolean skipPrawn;

    /** Skip DocBook 5 backend. */
    @Parameter(property = "ike.skip.docbook", defaultValue = "true")
    boolean skipDocbook;

    // ── HTML options ──────────────────────────────────────────────────

    /** TOC placement for HTML output. */
    @Parameter(property = "ike.asciidoc.htmlToc", defaultValue = "auto")
    String htmlToc;

    /** TOC depth for HTML output. */
    @Parameter(property = "ike.asciidoc.htmlTocLevels", defaultValue = "3")
    int htmlTocLevels;

    // ── Prawn PDF options ─────────────────────────────────────────────

    /** Prawn theme directory. */
    @Parameter(property = "ike.asciidoc.pdfThemeDir",
               defaultValue = "${project.build.directory}/ike-doc-resources/theme")
    File pdfThemeDir;

    /** Prawn theme name. */
    @Parameter(property = "ike.asciidoc.pdfTheme", defaultValue = "ike-default")
    String pdfTheme;

    /** Font directory for Prawn PDF. */
    @Parameter(property = "ike.asciidoc.pdfFontsDir",
               defaultValue = "${project.build.directory}/fonts")
    File pdfFontsDir;

    // ── Diagram / shared resources ────────────────────────────────────

    /** Kroki diagram server URL. */
    @Parameter(property = "ike.asciidoc.diagramServerUrl",
               defaultValue = "https://kroki.komet.sh")
    String diagramServerUrl;

    /** Shared docinfo directory. */
    @Parameter(property = "ike.asciidoc.sharedAsciidocDir",
               defaultValue = "${project.build.directory}/ike-doc-resources/shared-asciidoc")
    File sharedAsciidocDir;

    /** Ruby libraries to require. Defaults to asciidoctor-diagram. */
    @Parameter
    List<String> requires = List.of("asciidoctor-diagram");

    // ── Document naming ───────────────────────────────────────────────

    /** Output document base name. */
    @Parameter(property = "ike.document.name",
               defaultValue = "${project.artifactId}")
    String documentName;

    // ── Project attributes (injected by Maven) ────────────────────────

    @Parameter(defaultValue = "${project.version}", readonly = true)
    String projectVersion;

    @Parameter(defaultValue = "${project.name}", readonly = true)
    String projectName;

    // ── Validation ────────────────────────────────────────────────────

    /** Validate output for unresolved attributes, broken xrefs, etc. */
    @Parameter(property = "ike.asciidoc.validate", defaultValue = "true")
    boolean validate;

    /** Fail the build on any validation error. */
    @Parameter(property = "ike.asciidoc.strict", defaultValue = "false")
    boolean strict;

    // ── Output mode ──────────────────────────────────────────────────

    /**
     * Generate standalone HTML documents (with head/body shell).
     * Set to {@code false} for embedded body fragments suitable for
     * Doxia integration via {@code generatedSiteDirectory}.
     */
    @Parameter(property = "ike.asciidoc.standalone", defaultValue = "true")
    boolean standalone;

    // ── Additional attributes from POM ────────────────────────────────

    /** Additional AsciiDoc attributes merged with defaults. */
    @Parameter
    Map<String, String> attributes;

    /** Creates this goal instance. */
    public AsciidocMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("ike:asciidoc skipped");
            return;
        }
        if (!sourceDirectory.isDirectory()) {
            getLog().info("ike:asciidoc: source directory does not exist, skipping — "
                    + sourceDirectory);
            return;
        }

        getLog().info("ike:asciidoc — source: " + sourceDirectory);

        // Suppress JRuby/AsciidoctorJ java.util.logging output to stderr.
        // Our LogHandler routes messages through Maven's logger instead.
        Logger asciidoctorLogger = Logger.getLogger("asciidoctor");
        asciidoctorLogger.setUseParentHandlers(false);
        for (Handler h : asciidoctorLogger.getHandlers()) {
            asciidoctorLogger.removeHandler(h);
        }
        asciidoctorLogger.setLevel(Level.ALL);

        try (Asciidoctor asciidoctor = Asciidoctor.Factory.create()) {
            // Route AsciidoctorJ log messages through Maven's logger
            asciidoctor.registerLogHandler(new MavenLogHandler(getLog()));

            // Require Ruby libraries (e.g., asciidoctor-diagram)
            if (requires != null && !requires.isEmpty()) {
                asciidoctor.requireLibrary(requires.toArray(new String[0]));
            }

            // Register Koncept inline macro (safe for all backends)
            asciidoctor.javaExtensionRegistry()
                    .inlineMacro(KonceptInlineMacro.class);

            // Convert each enabled backend
            boolean hadErrors = false;
            if (!skipHtml) {
                hadErrors |= !tryConvert(asciidoctor, Backend.HTML, false);
            }
            if (!skipHtmlSingle) {
                hadErrors |= !tryConvert(asciidoctor, Backend.HTML, true);
            }
            if (!skipPrawn) {
                hadErrors |= !tryConvert(asciidoctor, Backend.PDF, false);
            }
            if (!skipDocbook) {
                hadErrors |= !tryConvert(asciidoctor, Backend.DOCBOOK, false);
            }
            if (hadErrors && strict) {
                throw new MojoExecutionException(
                        "ike:asciidoc: one or more backends failed (strict mode)");
            }
        }

        // Validate outputs
        if (validate) {
            validateOutputs();
        }
    }

    /**
     * Attempt a backend conversion, logging errors instead of failing.
     *
     * @return true if conversion succeeded, false on error
     */
    private boolean tryConvert(Asciidoctor asciidoctor, Backend backend, boolean singleFile) {
        try {
            convertBackend(asciidoctor, backend, singleFile);
            return true;
        } catch (MojoExecutionException e) {
            getLog().error("ike:asciidoc: " + backend + " conversion failed — "
                    + e.getMessage());
            return false;
        }
    }

    /**
     * Convert AsciiDoc sources for a single backend.
     *
     * @param asciidoctor the AsciidoctorJ instance
     * @param backend     target backend
     * @param singleFile  if true, generate single-file HTML (data-uri)
     */
    private void convertBackend(Asciidoctor asciidoctor, Backend backend, boolean singleFile)
            throws MojoExecutionException {

        File outDir;
        if (!standalone && backend == Backend.HTML && !singleFile) {
            // Embedded mode: write directly to outputDirectory for Doxia integration
            outDir = outputDirectory;
        } else {
            String subdir = singleFile ? "html-single" : backend.outputSubdir();
            outDir = new File(outputDirectory, subdir);
        }
        outDir.mkdirs();

        getLog().info("  backend: " + backend.asciidoctorName()
                + (singleFile ? " (single-file)" : "")
                + " → " + outDir);

        // Register/unregister postprocessor per backend
        if (backend.supportsPostprocessor()) {
            asciidoctor.javaExtensionRegistry()
                    .postprocessor(KonceptGlossaryProcessor.class);
        }

        try {
            Attributes attrs = buildAttributes(backend, singleFile);
            Options options = buildOptions(backend, outDir, attrs, singleFile);

            if (sourceDocumentName != null && (singleFile || backend == Backend.PDF)) {
                // Single document conversion
                File sourceFile = new File(sourceDirectory, sourceDocumentName);
                if (!sourceFile.isFile()) {
                    throw new MojoExecutionException(
                            "Source document not found: " + sourceFile);
                }
                asciidoctor.convertFile(sourceFile, options);
            } else {
                // Convert all .adoc files in source directory
                List<File> sourceFiles = scanAsciiDocFiles(sourceDirectory.toPath());
                for (File src : sourceFiles) {
                    asciidoctor.convertFile(src, options);
                }
            }
            // Embedded mode: rename .html → .xhtml for Doxia XHTML parser
            if (!standalone && backend == Backend.HTML && !singleFile) {
                renameHtmlToXhtml(outDir);
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "AsciidoctorJ conversion failed for backend " + backend, e);
        } finally {
            // Unregister postprocessor to prevent it from running on next backend
            if (backend.supportsPostprocessor()) {
                asciidoctor.unregisterAllExtensions();
                // Re-register the inline macro (unregisterAll removes everything)
                asciidoctor.javaExtensionRegistry()
                        .inlineMacro(KonceptInlineMacro.class);
            }
        }
    }

    /**
     * Build the AsciiDoc attributes for a given backend.
     */
    private Attributes buildAttributes(Backend backend, boolean singleFile) {
        AttributesBuilder ab = Attributes.builder();

        // Common attributes
        ab.attribute("project-version", projectVersion);
        ab.attribute("project-name", projectName);
        ab.attribute("source-highlighter", "coderay");
        ab.attribute("coderay-linenums-mode", "table");
        ab.attribute("icons", "font");
        ab.attribute("sectanchors", "true");
        ab.attribute("idprefix", "");
        ab.attribute("idseparator", "-");
        ab.attribute("allow-uri-read", "true");

        // Diagram attributes
        ab.attribute("diagram-server-url", diagramServerUrl);
        ab.attribute("diagram-server-type", "kroki_io");
        ab.attribute("diagram-format", "svg");
        ab.attribute("diagram-svg-type", "inline");
        // Disable PlantUML preprocessing — requires a companion service
        // (/plantumlpreprocessor) that self-hosted Kroki typically lacks.
        // Preprocessing is only needed for !include directives; standard
        // PlantUML renders fine without it.
        ab.attribute("plantuml-preprocess", "false");
        // Also set the non-prefixed form (block-level attribute override)
        ab.attribute("preprocess", "false");

        // Generated sources directory
        ab.attribute("generated",
                new File(outputDirectory.getParentFile(), "generated-sources/asciidoc")
                        .getAbsolutePath());
        ab.attribute("resources",
                new File(sourceDirectory.getParentFile(), "resources").getAbsolutePath());

        // Backend-specific attributes
        switch (backend) {
            case HTML -> {
                ab.attribute("toc", htmlToc);
                ab.attribute("toclevels", String.valueOf(htmlTocLevels));
                ab.linkCss(false);
                if (sharedAsciidocDir.isDirectory()) {
                    ab.attribute("docinfodir", sharedAsciidocDir.getAbsolutePath());
                    ab.attribute("docinfo", "shared");
                }
                ab.attribute("ike-pdf-renderer", "html5");
                if (singleFile) {
                    ab.dataUri(true);
                }
            }
            case PDF -> {
                ab.attribute("pdf-themesdir", pdfThemeDir.getAbsolutePath());
                ab.attribute("pdf-theme", pdfTheme);
                ab.attribute("pdf-fontsdir", pdfFontsDir.getAbsolutePath());
                ab.attribute("media", "prepress");
                ab.attribute("optimize", "true");
                ab.attribute("ike-pdf-renderer", "prawn");
            }
            case DOCBOOK -> {
                ab.attribute("doctype", "book");
                ab.attribute("ike-pdf-renderer", "docbook");
            }
        }

        // Merge user-provided attributes (override defaults)
        if (attributes != null) {
            attributes.forEach(ab::attribute);
        }

        return ab.build();
    }

    /**
     * Build AsciidoctorJ Options for a given backend and output directory.
     */
    private Options buildOptions(Backend backend, File outDir,
                                 Attributes attrs, boolean singleFile) {
        OptionsBuilder builder = Options.builder()
                .backend(backend.asciidoctorName())
                .toDir(outDir)
                .safe(SafeMode.UNSAFE)
                .mkDirs(true)
                .standalone(standalone)
                .baseDir(baseDir)
                .attributes(attrs);

        // For single-document modes, name the output using documentName
        // (e.g., "example-project.html" instead of "index.html")
        if (sourceDocumentName != null && (singleFile || backend == Backend.PDF)) {
            builder.toFile(new File(outDir,
                    documentName + "." + outputExtension(backend)));
        }

        return builder.build();
    }

    /** HTML void elements that must be self-closed for XHTML compliance. */
    private static final java.util.regex.Pattern VOID_ELEMENT =
            java.util.regex.Pattern.compile(
                    "<(area|base|br|col|embed|hr|img|input|link|meta|source|track|wbr)"
                    + "(\\s[^>]*)?>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Convert embedded HTML fragments to Doxia-compatible XHTML files:
     * wrap in a single root {@code <div>}, self-close void elements,
     * and rename {@code .html → .xhtml} so the Doxia XHTML parser
     * discovers them in {@code generatedSiteDirectory/xhtml/}.
     */
    private void renameHtmlToXhtml(File dir) throws MojoExecutionException {
        File[] htmlFiles = dir.listFiles((d, name) -> name.endsWith(".html"));
        if (htmlFiles == null) return;
        for (File html : htmlFiles) {
            try {
                String content = Files.readString(html.toPath());
                // Self-close void elements: <br> → <br/>, <col ...> → <col .../>
                content = VOID_ELEMENT.matcher(content).replaceAll(mr -> {
                    String tag = mr.group(1);
                    String attrs = mr.group(2);
                    if (attrs == null || attrs.isEmpty()) return "<" + tag + "/>";
                    // Strip trailing space before closing
                    return "<" + tag + attrs.stripTrailing() + "/>";
                });
                // Wrap in root <div> with an <h1> title (Sentry skin
                // expects h1 for navigation). Extract title from first
                // <h2> if present, or use the filename.
                String title = extractTitle(content, html.getName());
                String wrapped = "<div class=\"ike-asciidoc\">\n"
                        + "<h1 id=\"doc-title\">" + title + "</h1>\n"
                        + content + "\n</div>\n";
                String xhtmlName = html.getName().replaceFirst("\\.html$", ".xhtml");
                Path xhtml = html.toPath().resolveSibling(xhtmlName);
                Files.writeString(xhtml, wrapped);
                Files.delete(html.toPath());
                getLog().debug("Wrapped and renamed " + html.getName() + " → " + xhtmlName);
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to convert " + html + " to XHTML", e);
            }
        }
    }

    /** Extract a title from embedded HTML content for use as the page h1. */
    private String extractTitle(String html, String fallbackFilename) {
        // Asciidoctor's embedded mode omits the doc title (= Title) but
        // includes section titles as <h2>. The first <h2> is usually the
        // first section heading, not the doc title. Instead, look for the
        // document title in the preamble or use the AsciiDoc = Title which
        // Asciidoctor puts in a <h1> with id="document-title" even in
        // embedded mode if showtitle is set. Failing that, derive from
        // the source filename.
        var h1 = java.util.regex.Pattern.compile("<h1[^>]*>([^<]+)</h1>")
                .matcher(html);
        if (h1.find()) return h1.group(1);
        // Fall back to filename without extension
        return fallbackFilename.replaceFirst("\\.[^.]+$", "")
                .replace('-', ' ').replace('_', ' ');
    }

    private String outputExtension(Backend backend) {
        return switch (backend) {
            case HTML -> "html";
            case PDF -> "pdf";
            case DOCBOOK -> "xml";
        };
    }

    /**
     * Run output validation on all generated directories.
     */
    private void validateOutputs() throws MojoExecutionException {
        OutputValidator validator = new OutputValidator();
        List<OutputValidator.Issue> allIssues = new java.util.ArrayList<>();

        for (Backend backend : Backend.values()) {
            boolean shouldCheck = switch (backend) {
                case HTML -> !skipHtml;
                case PDF -> !skipPrawn;
                case DOCBOOK -> !skipDocbook;
            };
            if (!shouldCheck) continue;

            Path dir = outputDirectory.toPath().resolve(backend.outputSubdir());
            try {
                List<OutputValidator.Issue> issues = validator.validate(dir, backend);
                allIssues.addAll(issues);
            } catch (IOException e) {
                getLog().warn("Validation failed for " + dir + ": " + e.getMessage());
            }
        }

        if (!allIssues.isEmpty()) {
            long errors = allIssues.stream()
                    .filter(i -> i.severity() == OutputValidator.Severity.ERROR)
                    .count();
            long warnings = allIssues.stream()
                    .filter(i -> i.severity() == OutputValidator.Severity.WARNING)
                    .count();

            for (OutputValidator.Issue issue : allIssues) {
                if (issue.severity() == OutputValidator.Severity.ERROR) {
                    getLog().error(issue.toString());
                } else {
                    getLog().warn(issue.toString());
                }
            }

            getLog().info("Validation: " + errors + " error(s), " + warnings + " warning(s)");

            if (strict && errors > 0) {
                throw new MojoExecutionException(
                        "ike:asciidoc validation failed with " + errors + " error(s)");
            }
        } else {
            getLog().info("Validation: clean");
        }
    }

    // ── Log handler ────────────────────────────────────────────────

    /**
     * Routes AsciidoctorJ log messages through Maven's logger with
     * proper severity mapping. Eliminates the raw
     * {@code [WARNING] [stderr] SEVERE:} output from JRuby.
     */
    static class MavenLogHandler implements LogHandler {

        private final Log log;
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        MavenLogHandler(Log log) {
            this.log = log;
        }

        @Override
        public void log(LogRecord record) {
            String source = "";
            if (record.getCursor() != null && record.getCursor().getFile() != null) {
                source = record.getCursor().getFile();
                if (record.getCursor().getLineNumber() > 0) {
                    source += ":" + record.getCursor().getLineNumber();
                }
                source += " — ";
            }
            String msg = source + record.getMessage();

            // Deduplicate: AsciidoctorJ logs the same error from parser and converter
            String key = record.getSeverity() + "|" + msg;
            if (!seen.add(key)) return;

            Severity severity = record.getSeverity();
            if (severity == Severity.ERROR || severity == Severity.FATAL) {
                log.error(msg);
            } else if (severity == Severity.WARN) {
                log.warn(msg);
            } else if (severity == Severity.INFO) {
                log.info(msg);
            } else {
                log.debug(msg);
            }
        }
    }

    // ── File scanning ───────────────────────────────────────────────

    /**
     * Scan a directory for .adoc files, excluding partials (files whose
     * name starts with underscore) and files in underscore-prefixed
     * directories.
     *
     * @param root the directory to scan
     * @return list of .adoc files
     */
    static List<File> scanAsciiDocFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().endsWith(".adoc"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .filter(p -> {
                        // Exclude files in _partial directories
                        for (Path part : root.relativize(p)) {
                            if (part.toString().startsWith("_")) return false;
                        }
                        return true;
                    })
                    .map(Path::toFile)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + root, e);
        }
    }
}
