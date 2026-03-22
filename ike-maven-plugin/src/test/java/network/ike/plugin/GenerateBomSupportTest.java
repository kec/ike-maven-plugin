package network.ike.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure functions extracted from {@link GenerateBomMojo}:
 * {@code escapeXml()} and {@code buildBomXml()}.
 */
class GenerateBomSupportTest {

    // ── escapeXml ────────────────────────────────────────────────────

    @Test
    void escapeXml_ampersand() {
        assertThat(GenerateBomMojo.escapeXml("AT&T"))
                .isEqualTo("AT&amp;T");
    }

    @Test
    void escapeXml_lessThan() {
        assertThat(GenerateBomMojo.escapeXml("a < b"))
                .isEqualTo("a &lt; b");
    }

    @Test
    void escapeXml_greaterThan() {
        assertThat(GenerateBomMojo.escapeXml("a > b"))
                .isEqualTo("a &gt; b");
    }

    @Test
    void escapeXml_allSpecialChars() {
        assertThat(GenerateBomMojo.escapeXml("a & b < c > d"))
                .isEqualTo("a &amp; b &lt; c &gt; d");
    }

    @Test
    void escapeXml_null_returnsEmpty() {
        assertThat(GenerateBomMojo.escapeXml(null))
                .isEmpty();
    }

    @Test
    void escapeXml_plainText_unchanged() {
        assertThat(GenerateBomMojo.escapeXml("Hello World"))
                .isEqualTo("Hello World");
    }

    // ── buildBomXml ──────────────────────────────────────────────────

    @Test
    void buildBomXml_emptyEntries_producesValidXml() {
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20-SNAPSHOT",
                "IKE BOM", "Bill of Materials", "https://ike.network",
                List.of());

        assertThat(xml)
                .startsWith("<?xml version=\"1.0\"")
                .contains("<groupId>network.ike</groupId>")
                .contains("<artifactId>ike-bom</artifactId>")
                .contains("<version>20-SNAPSHOT</version>")
                .contains("<packaging>pom</packaging>")
                .contains("<name>IKE BOM</name>")
                .contains("<description>Bill of Materials</description>")
                .contains("<url>https://ike.network</url>")
                .contains("<dependencyManagement>")
                .contains("</dependencyManagement>")
                .endsWith("</project>\n");
    }

    @Test
    void buildBomXml_plainJarEntry() {
        BomEntry jar = new BomEntry(
                "network.ike", "minimal-fonts", "1.0.0",
                null, "jar", null);

        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20",
                "BOM", null, null,
                List.of(jar));

        assertThat(xml)
                .contains("<groupId>network.ike</groupId>")
                .contains("<artifactId>minimal-fonts</artifactId>")
                .contains("<version>1.0.0</version>")
                .doesNotContain("<classifier>")
                .doesNotContain("<type>")
                .doesNotContain("<scope>");
    }

    @Test
    void buildBomXml_classifiedZipEntry() {
        BomEntry zip = new BomEntry(
                "network.ike", "ike-build-standards", "20",
                "claude", "zip", null);

        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20",
                "BOM", null, null,
                List.of(zip));

        assertThat(xml)
                .contains("<classifier>claude</classifier>")
                .contains("<type>zip</type>");
    }

    @Test
    void buildBomXml_testScopedEntry() {
        BomEntry test = new BomEntry(
                "org.junit.jupiter", "junit-jupiter", "5.11.4",
                null, "jar", "test");

        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20",
                "BOM", null, null,
                List.of(test));

        assertThat(xml)
                .contains("<scope>test</scope>")
                .doesNotContain("<classifier>")
                .doesNotContain("<type>");
    }

    @Test
    void buildBomXml_nullDescription_omitted() {
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "1",
                "BOM", null, null,
                List.of());

        assertThat(xml)
                .doesNotContain("<description>");
    }

    @Test
    void buildBomXml_nullUrl_emptyElement() {
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "1",
                "BOM", null, null,
                List.of());

        assertThat(xml)
                .contains("<url></url>");
    }

    @Test
    void buildBomXml_multipleEntries_allPresent() {
        List<BomEntry> entries = List.of(
                new BomEntry("a", "first", "1.0", null, "jar", null),
                new BomEntry("b", "second", "2.0", null, "jar", null),
                new BomEntry("c", "third", "3.0", null, "jar", null));

        String xml = GenerateBomMojo.buildBomXml(
                "g", "a", "1", "N", null, null, entries);

        assertThat(xml)
                .contains("<artifactId>first</artifactId>")
                .contains("<artifactId>second</artifactId>")
                .contains("<artifactId>third</artifactId>");
    }

    @Test
    void buildBomXml_nameWithSpecialChars_escaped() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "a", "1",
                "IKE <BOM> & More", null, null,
                List.of());

        assertThat(xml)
                .contains("<name>IKE &lt;BOM&gt; &amp; More</name>");
    }
}
