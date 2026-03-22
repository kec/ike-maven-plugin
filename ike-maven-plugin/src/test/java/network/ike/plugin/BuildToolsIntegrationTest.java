package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for build-tools Mojos using real file I/O.
 *
 * <p>Each test creates files in a {@link TempDir}, configures the Mojo
 * with package-private field access, executes it, and verifies the
 * results on disk.
 */
class BuildToolsIntegrationTest {

    // ── FixSvgMojo ──────────────────────────────────────────────────

    @Test
    void fixSvg_removesRects(@TempDir Path tmp) throws Exception {
        Path htmlFile = tmp.resolve("document.html");
        Files.writeString(htmlFile,
                "<html><svg><g><rect/><text>hello</text><rect/></g></svg></html>");

        FixSvgMojo mojo = new FixSvgMojo();
        mojo.htmlFile = htmlFile.toFile();
        mojo.setLog(new SilentLog());
        mojo.execute();

        String result = Files.readString(htmlFile);
        assertThat(result)
                .doesNotContain("<rect/>")
                .contains("<text>hello</text>");
    }

    @Test
    void fixSvg_missingFile_skips(@TempDir Path tmp) throws Exception {
        FixSvgMojo mojo = new FixSvgMojo();
        mojo.htmlFile = tmp.resolve("missing.html").toFile();
        mojo.setLog(new SilentLog());
        // Should not throw
        mojo.execute();
    }

    // ── PatchDocbookMojo ────────────────────────────────────────────

    @Test
    void patchDocbook_patchesBothFiles(@TempDir Path tmp) throws Exception {
        // Create fo/ subdirectory with mock XSL files
        Path foDir = tmp.resolve("fo");
        Files.createDirectories(foDir);

        Files.writeString(foDir.resolve("docbook.xsl"),
                "<xsl:stylesheet>\n"
                + "<xsl:include href=\"../common/utility.xsl\"/>\n"
                + "<xsl:template match=\"/\"/>\n"
                + "</xsl:stylesheet>");

        Files.writeString(foDir.resolve("math.xsl"),
                "<xsl:stylesheet>\n"
                + "<xsl:template match=\"equation\">\n"
                + "<xsl:variable name=\"output.delims\">\n"
                + "  <xsl:call-template name=\"pi.dblatex_equation\"/>\n"
                + "</xsl:variable>\n"
                + "<fo:block/>\n"
                + "</xsl:template>\n"
                + "</xsl:stylesheet>");

        PatchDocbookMojo mojo = new PatchDocbookMojo();
        mojo.docbookDir = tmp.toFile();
        mojo.setLog(new SilentLog());
        mojo.execute();

        assertThat(Files.readString(foDir.resolve("docbook.xsl")))
                .doesNotContain("utility.xsl")
                .contains("<xsl:template match=\"/\"/>");

        assertThat(Files.readString(foDir.resolve("math.xsl")))
                .doesNotContain("output.delims")
                .contains("<fo:block/>");
    }

    @Test
    void patchDocbook_missingDir_skips(@TempDir Path tmp) throws Exception {
        PatchDocbookMojo mojo = new PatchDocbookMojo();
        mojo.docbookDir = tmp.resolve("nonexistent").toFile();
        mojo.setLog(new SilentLog());
        // Should not throw
        mojo.execute();
    }

    // ── ScanRendererLogsMojo ────────────────────────────────────────

    @Test
    void scanLogs_reportsErrors(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("renderer-prince.log"),
                "INFO: page 1\nERROR: missing font\nINFO: page 2\n");
        Files.writeString(tmp.resolve("renderer-fop.log"),
                "INFO: rendering\nINFO: done\n");

        ScanRendererLogsMojo mojo = new ScanRendererLogsMojo();
        mojo.logsDir = tmp.toFile();
        mojo.setLog(new SilentLog());
        // Should not throw — informational only
        mojo.execute();
    }

    @Test
    void scanLogs_missingDir_skips(@TempDir Path tmp) throws Exception {
        ScanRendererLogsMojo mojo = new ScanRendererLogsMojo();
        mojo.logsDir = tmp.resolve("nonexistent").toFile();
        mojo.setLog(new SilentLog());
        // Should not throw
        mojo.execute();
    }

    // ── CopyDocsToSiteMojo ──────────────────────────────────────────

    @Test
    void copyDocs_copiesFiles(@TempDir Path tmp) throws Exception {
        Path genDocs = tmp.resolve("generated-docs");
        Path siteDir = tmp.resolve("site");
        Files.createDirectories(genDocs);

        Files.writeString(genDocs.resolve("doc.html"), "<html/>");
        Files.writeString(genDocs.resolve("diagram.svg"), "<svg/>");
        Files.writeString(genDocs.resolve("image.png"), "PNG");
        Files.writeString(genDocs.resolve("photo.jpg"), "JPG");
        Files.writeString(genDocs.resolve("style.css"), "body{}");
        Files.writeString(genDocs.resolve("data.xml"), "<data/>"); // should NOT be copied

        CopyDocsToSiteMojo mojo = new CopyDocsToSiteMojo();
        mojo.generatedDocsDir = genDocs.toFile();
        mojo.siteDir = siteDir.toFile();
        mojo.setLog(new SilentLog());
        mojo.execute();

        Path dest = siteDir.resolve("docs");
        assertThat(dest.resolve("doc.html")).exists();
        assertThat(dest.resolve("diagram.svg")).exists();
        assertThat(dest.resolve("image.png")).exists();
        assertThat(dest.resolve("photo.jpg")).exists();
        assertThat(dest.resolve("style.css")).exists();
        assertThat(dest.resolve("data.xml")).doesNotExist();
    }

    @Test
    void copyDocs_missingDir_skips(@TempDir Path tmp) throws Exception {
        CopyDocsToSiteMojo mojo = new CopyDocsToSiteMojo();
        mojo.generatedDocsDir = tmp.resolve("nonexistent").toFile();
        mojo.siteDir = tmp.resolve("site").toFile();
        mojo.setLog(new SilentLog());
        // Should not throw
        mojo.execute();
    }

    // ── InjectBreadcrumbMojo ────────────────────────────────────────

    @Test
    void injectBreadcrumb_injectsLink(@TempDir Path tmp) throws Exception {
        Path htmlFile = tmp.resolve("index.html");
        Files.writeString(htmlFile,
                "<html><body>"
                + "<div class=\"breadcrumb\" id=\"breadcrumb\">"
                + "<a href=\"index.html\">All packages</a>"
                + "</div></body></html>");

        InjectBreadcrumbMojo mojo = new InjectBreadcrumbMojo();
        mojo.targetDir = tmp.toFile();
        mojo.link = "../index.html";
        mojo.label = "\u2190 Project Site";
        mojo.setLog(new SilentLog());
        mojo.execute();

        String result = Files.readString(htmlFile);
        assertThat(result)
                .contains("<a href=\"../index.html\"")
                .contains("\u2190 Project Site</a> | ")
                .contains("<a href=\"index.html\">All packages</a>");
    }

    @Test
    void injectBreadcrumb_noBreadcrumbDiv_unchanged(@TempDir Path tmp)
            throws Exception {
        Path htmlFile = tmp.resolve("plain.html");
        String original = "<html><body><div>no breadcrumb</div></body></html>";
        Files.writeString(htmlFile, original);

        InjectBreadcrumbMojo mojo = new InjectBreadcrumbMojo();
        mojo.targetDir = tmp.toFile();
        mojo.link = "../index.html";
        mojo.label = "\u2190 Project Site";
        mojo.setLog(new SilentLog());
        mojo.execute();

        assertThat(Files.readString(htmlFile)).isEqualTo(original);
    }

    @Test
    void injectBreadcrumb_missingDir_skips(@TempDir Path tmp) throws Exception {
        InjectBreadcrumbMojo mojo = new InjectBreadcrumbMojo();
        mojo.targetDir = tmp.resolve("nonexistent").toFile();
        mojo.link = "../index.html";
        mojo.label = "\u2190 Project Site";
        mojo.setLog(new SilentLog());
        // Should not throw
        mojo.execute();
    }

    // ── Silent log for test execution ───────────────────────────────

    /**
     * Minimal Maven log that discards all output.
     */
    private static class SilentLog implements org.apache.maven.plugin.logging.Log {
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(CharSequence content) {}
        @Override public void debug(CharSequence content, Throwable error) {}
        @Override public void debug(Throwable error) {}
        @Override public boolean isInfoEnabled() { return false; }
        @Override public void info(CharSequence content) {}
        @Override public void info(CharSequence content, Throwable error) {}
        @Override public void info(Throwable error) {}
        @Override public boolean isWarnEnabled() { return false; }
        @Override public void warn(CharSequence content) {}
        @Override public void warn(CharSequence content, Throwable error) {}
        @Override public void warn(Throwable error) {}
        @Override public boolean isErrorEnabled() { return false; }
        @Override public void error(CharSequence content) {}
        @Override public void error(CharSequence content, Throwable error) {}
        @Override public void error(Throwable error) {}
    }
}
