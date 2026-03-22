package network.ike.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions extracted from the build-tools Mojos:
 * {@link FixSvgMojo}, {@link PatchDocbookMojo},
 * {@link ScanRendererLogsMojo}, and {@link InjectBreadcrumbMojo}.
 */
class BuildToolsSupportTest {

    // ── FixSvgMojo.removeBareRects ──────────────────────────────────

    @Test
    void removeBareRects_emptyString() {
        assertThat(FixSvgMojo.removeBareRects(""))
                .isEmpty();
    }

    @Test
    void removeBareRects_noRects() {
        String html = "<svg><rect width=\"10\" height=\"10\"/></svg>";
        assertThat(FixSvgMojo.removeBareRects(html))
                .isEqualTo(html);
    }

    @Test
    void removeBareRects_singleRect() {
        assertThat(FixSvgMojo.removeBareRects("<g><rect/></g>"))
                .isEqualTo("<g></g>");
    }

    @Test
    void removeBareRects_multipleRects() {
        assertThat(FixSvgMojo.removeBareRects("<g><rect/><text>hi</text><rect/></g>"))
                .isEqualTo("<g><text>hi</text></g>");
    }

    // ── FixSvgMojo.countBareRects ───────────────────────────────────

    @Test
    void countBareRects_emptyString() {
        assertThat(FixSvgMojo.countBareRects("")).isZero();
    }

    @Test
    void countBareRects_noRects() {
        assertThat(FixSvgMojo.countBareRects("<svg></svg>")).isZero();
    }

    @Test
    void countBareRects_singleRect() {
        assertThat(FixSvgMojo.countBareRects("<g><rect/></g>")).isEqualTo(1);
    }

    @Test
    void countBareRects_multipleRects() {
        assertThat(FixSvgMojo.countBareRects("<rect/> text <rect/> more <rect/>"))
                .isEqualTo(3);
    }

    // ── PatchDocbookMojo.removeUtilityInclude ───────────────────────

    @Test
    void removeUtilityInclude_withInclude() {
        String xsl = """
                <xsl:stylesheet>
                <xsl:include href="../common/utility.xsl"/>
                <xsl:template match="/">
                </xsl:template>
                </xsl:stylesheet>""";
        String result = PatchDocbookMojo.removeUtilityInclude(xsl);
        assertThat(result)
                .doesNotContain("utility.xsl")
                .contains("<xsl:template match=\"/\">");
    }

    @Test
    void removeUtilityInclude_withoutInclude() {
        String xsl = "<xsl:stylesheet><xsl:template/></xsl:stylesheet>";
        assertThat(PatchDocbookMojo.removeUtilityInclude(xsl))
                .isEqualTo(xsl);
    }

    // ── PatchDocbookMojo.removeDeadVariable ─────────────────────────

    @Test
    void removeDeadVariable_withVariable() {
        String xsl = """
                <xsl:template match="equation">
                <xsl:variable name="output.delims">
                  <xsl:call-template name="pi.dblatex_equation">
                    <xsl:with-param name="node" select="."/>
                  </xsl:call-template>
                </xsl:variable>
                <fo:block/>
                </xsl:template>""";
        String result = PatchDocbookMojo.removeDeadVariable(xsl);
        assertThat(result)
                .doesNotContain("output.delims")
                .contains("<fo:block/>");
    }

    @Test
    void removeDeadVariable_withoutVariable() {
        String xsl = "<xsl:template><fo:block/></xsl:template>";
        assertThat(PatchDocbookMojo.removeDeadVariable(xsl))
                .isEqualTo(xsl);
    }

    // ── ScanRendererLogsMojo.countErrors ─────────────────────────────

    @Test
    void countErrors_cleanLog() {
        String log = "INFO: Rendering page 1\nINFO: Rendering page 2\nINFO: Done";
        assertThat(ScanRendererLogsMojo.countErrors(log)).isZero();
    }

    @Test
    void countErrors_withErrors() {
        String log = "INFO: Start\nERROR: missing font\nINFO: page 2\nFATAL: out of memory";
        assertThat(ScanRendererLogsMojo.countErrors(log)).isEqualTo(2);
    }

    @Test
    void countErrors_mixedPatterns() {
        String log = """
                INFO: Start
                WARNING: image not found
                ERROR: font exception
                INFO: page failed to render
                INFO: Done""";
        // "not found" on line 2, "ERROR" + "exception" on line 3, "failed" on line 4
        assertThat(ScanRendererLogsMojo.countErrors(log)).isEqualTo(3);
    }

    @Test
    void countErrors_emptyLog() {
        assertThat(ScanRendererLogsMojo.countErrors("")).isZero();
    }

    // ── ScanRendererLogsMojo.formatSummary ───────────────────────────

    @Test
    void formatSummary_noErrors() {
        assertThat(ScanRendererLogsMojo.formatSummary("prince", 42, 0))
                .isEqualTo("  [prince] OK (42 lines)");
    }

    @Test
    void formatSummary_withErrors() {
        assertThat(ScanRendererLogsMojo.formatSummary("fop", 100, 3))
                .isEqualTo("  [fop] 3 error(s)");
    }

    @Test
    void formatSummary_singleError() {
        assertThat(ScanRendererLogsMojo.formatSummary("prawn", 10, 1))
                .isEqualTo("  [prawn] 1 error(s)");
    }

    // ── ScanRendererLogsMojo.extractRendererName ─────────────────────

    @Test
    void extractRendererName_standard() {
        assertThat(ScanRendererLogsMojo.extractRendererName("renderer-prince.log"))
                .isEqualTo("prince");
    }

    @Test
    void extractRendererName_hyphenated() {
        assertThat(ScanRendererLogsMojo.extractRendererName("renderer-pdf-fop.log"))
                .isEqualTo("pdf-fop");
    }

    // ── CopyDocsToSiteMojo.shouldCopy ───────────────────────────────

    @Test
    void shouldCopy_htmlFile() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("document.html")).isTrue();
    }

    @Test
    void shouldCopy_svgFile() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("diagram.svg")).isTrue();
    }

    @Test
    void shouldCopy_pngFile() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("image.png")).isTrue();
    }

    @Test
    void shouldCopy_jpgFile() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("photo.jpg")).isTrue();
    }

    @Test
    void shouldCopy_cssFile() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("style.css")).isTrue();
    }

    @Test
    void shouldCopy_javaFile_false() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("Main.java")).isFalse();
    }

    @Test
    void shouldCopy_xmlFile_false() {
        assertThat(CopyDocsToSiteMojo.shouldCopy("pom.xml")).isFalse();
    }

    // ── InjectBreadcrumbMojo.injectBreadcrumb ───────────────────────

    @Test
    void injectBreadcrumb_withBreadcrumbDiv() {
        String html = "<html><body><div class=\"breadcrumb\" id=\"breadcrumb\">"
                + "<a href=\"index.html\">All packages</a></div></body></html>";
        String result = InjectBreadcrumbMojo.injectBreadcrumb(html,
                "../index.html", "\u2190 Project Site");
        assertThat(result)
                .contains("<a href=\"../index.html\"")
                .contains("\u2190 Project Site</a> | ")
                .contains("<a href=\"index.html\">All packages</a>");
    }

    @Test
    void injectBreadcrumb_withoutBreadcrumbDiv() {
        String html = "<html><body><div>no breadcrumb here</div></body></html>";
        assertThat(InjectBreadcrumbMojo.injectBreadcrumb(html,
                "../index.html", "\u2190 Project Site"))
                .isEqualTo(html);
    }

    @Test
    void injectBreadcrumb_customLinkAndLabel() {
        String html = "<div class=\"breadcrumb\" id=\"breadcrumb\">content</div>";
        String result = InjectBreadcrumbMojo.injectBreadcrumb(html,
                "../../home.html", "Back");
        assertThat(result)
                .contains("<a href=\"../../home.html\"")
                .contains("Back</a> | ");
    }
}
