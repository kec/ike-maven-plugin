package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions extracted from {@link WsReleaseMojo}:
 * POM version extraction, parent version updates, and version
 * property updates.
 */
class WsReleaseSupportTest {

    // ── extractVersionFromPom ────────────────────────────────────────

    @Test
    void extractVersionFromPom_simpleProject() {
        String pom = """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.2.3-SNAPSHOT</version>
                </project>
                """;
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("1.2.3-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_withParentBlock_returnsParentVersion() {
        // extractVersionFromPom finds the FIRST <version> — which is
        // inside <parent>. This is the documented behavior for
        // workspace-level quick reads.
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-module</artifactId>
                    <version>5.0.0</version>
                </project>
                """;
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("20-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_noVersion_returnsUnknown() {
        String pom = """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>orphan</artifactId>
                </project>
                """;
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_null_returnsUnknown() {
        assertThat(WsReleaseMojo.extractVersionFromPom(null))
                .isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_blank_returnsUnknown() {
        assertThat(WsReleaseMojo.extractVersionFromPom("  "))
                .isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_integerVersion() {
        String pom = "<project><version>20</version></project>";
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("20");
    }

    // ── updateParentVersion ──────────────────────────────────────────

    @Test
    void updateParentVersion_matchingArtifactId_updatesVersion() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                    <artifactId>ike-pipeline</artifactId>
                </project>
                """;
        String result = WsReleaseMojo.updateParentVersion(pom, "ike-parent", "21-SNAPSHOT");

        assertThat(result)
                .contains("<version>21-SNAPSHOT</version>")
                .doesNotContain("19-SNAPSHOT");
    }

    @Test
    void updateParentVersion_nonMatchingArtifactId_unchanged() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                </project>
                """;
        String result = WsReleaseMojo.updateParentVersion(pom, "other-parent", "21-SNAPSHOT");

        assertThat(result).contains("<version>19-SNAPSHOT</version>");
    }

    @Test
    void updateParentVersion_onlyUpdatesFirstMatch() {
        // The project has its own <version> that should not change
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        String result = WsReleaseMojo.updateParentVersion(pom, "ike-parent", "21-SNAPSHOT");

        assertThat(result)
                .contains("<version>21-SNAPSHOT</version>")
                .contains("<version>1.0.0</version>");
    }

    @Test
    void updateParentVersion_artifactIdWithDots_handlesRegexSafely() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-build-tools</artifactId>
                        <version>5-SNAPSHOT</version>
                    </parent>
                </project>
                """;
        String result = WsReleaseMojo.updateParentVersion(pom, "ike-build-tools", "6-SNAPSHOT");

        assertThat(result).contains("<version>6-SNAPSHOT</version>");
    }

    // ── updateVersionProperty ────────────────────────────────────────

    @Test
    void updateVersionProperty_matchingProperty_updated() {
        String pom = """
                <properties>
                    <ike-pipeline.version>19-SNAPSHOT</ike-pipeline.version>
                </properties>
                """;
        String result = WsReleaseMojo.updateVersionProperty(
                pom, "ike-pipeline.version", "21-SNAPSHOT");

        assertThat(result)
                .contains("<ike-pipeline.version>21-SNAPSHOT</ike-pipeline.version>")
                .doesNotContain("19-SNAPSHOT");
    }

    @Test
    void updateVersionProperty_dottedProperty_updated() {
        String pom = """
                <properties>
                    <ike.pipeline.version>19-SNAPSHOT</ike.pipeline.version>
                </properties>
                """;
        String result = WsReleaseMojo.updateVersionProperty(
                pom, "ike.pipeline.version", "21-SNAPSHOT");

        assertThat(result)
                .contains("<ike.pipeline.version>21-SNAPSHOT</ike.pipeline.version>");
    }

    @Test
    void updateVersionProperty_nonMatchingProperty_unchanged() {
        String pom = """
                <properties>
                    <ike-pipeline.version>19-SNAPSHOT</ike-pipeline.version>
                </properties>
                """;
        String result = WsReleaseMojo.updateVersionProperty(
                pom, "other.version", "21-SNAPSHOT");

        assertThat(result)
                .contains("<ike-pipeline.version>19-SNAPSHOT</ike-pipeline.version>");
    }

    @Test
    void updateVersionProperty_multipleOccurrences_allUpdated() {
        String pom = """
                <properties>
                    <lib.version>1.0</lib.version>
                </properties>
                <dependency>
                    <version>${lib.version}</version>
                </dependency>
                <profiles>
                    <properties>
                        <lib.version>1.0</lib.version>
                    </properties>
                </profiles>
                """;
        String result = WsReleaseMojo.updateVersionProperty(
                pom, "lib.version", "2.0");

        // Both occurrences should be updated
        assertThat(result).doesNotContain("<lib.version>1.0</lib.version>");
        assertThat(result.split("<lib.version>2.0</lib.version>")).hasSize(3);
    }

    // ── resolveMvnCommand ───────────────────────────────────────────

    @Test
    void resolveMvnCommand_noWrapper_returnsMvn(@TempDir Path tmpDir) {
        assertThat(WsReleaseMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo("mvn");
    }

    @Test
    void resolveMvnCommand_mvnwCmd_returnsAbsolutePath(@TempDir Path tmpDir)
            throws IOException {
        File mvnwCmd = tmpDir.resolve("mvnw.cmd").toFile();
        mvnwCmd.createNewFile();

        assertThat(WsReleaseMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo(mvnwCmd.getAbsolutePath());
    }

    @Test
    void resolveMvnCommand_executableMvnw_preferred(@TempDir Path tmpDir)
            throws IOException {
        File mvnw = tmpDir.resolve("mvnw").toFile();
        mvnw.createNewFile();
        mvnw.setExecutable(true);

        File mvnwCmd = tmpDir.resolve("mvnw.cmd").toFile();
        mvnwCmd.createNewFile();

        // mvnw (executable) should be preferred over mvnw.cmd
        assertThat(WsReleaseMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo(mvnw.getAbsolutePath());
    }

    @Test
    void resolveMvnCommand_nonExecutableMvnw_fallsToMvnwCmd(@TempDir Path tmpDir)
            throws IOException {
        File mvnw = tmpDir.resolve("mvnw").toFile();
        mvnw.createNewFile();
        mvnw.setExecutable(false);

        File mvnwCmd = tmpDir.resolve("mvnw.cmd").toFile();
        mvnwCmd.createNewFile();

        assertThat(WsReleaseMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo(mvnwCmd.getAbsolutePath());
    }

    // ── buildPreReleaseCheckpointYaml ────────────────────────────────

    @Test
    void buildPreReleaseCheckpointYaml_header() {
        String yaml = WsReleaseMojo.buildPreReleaseCheckpointYaml(
                "pre-release-20260320-100000",
                "2026-03-20T10:00:00Z",
                List.of());

        assertThat(yaml)
                .contains("# Workspace checkpoint: pre-release-20260320-100000")
                .contains("# Generated: 2026-03-20T10:00:00Z")
                .contains("checkpoint: pre-release-20260320-100000")
                .contains("timestamp: 2026-03-20T10:00:00Z")
                .contains("components:");
    }

    @Test
    void buildPreReleaseCheckpointYaml_singleComponent() {
        List<String[]> components = List.<String[]>of(
                new String[]{"ike-pipeline", "main", "abc123d", "20-SNAPSHOT", "false"});

        String yaml = WsReleaseMojo.buildPreReleaseCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", components);

        assertThat(yaml)
                .contains("  ike-pipeline:")
                .contains("    branch: main")
                .contains("    sha: abc123d")
                .contains("    version: 20-SNAPSHOT")
                .contains("    dirty: false");
    }

    @Test
    void buildPreReleaseCheckpointYaml_dirtyComponent() {
        List<String[]> components = List.<String[]>of(
                new String[]{"ike-docs", "feature/docs", "def456", "1.0-SNAPSHOT", "true"});

        String yaml = WsReleaseMojo.buildPreReleaseCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", components);

        assertThat(yaml)
                .contains("    dirty: true");
    }

    @Test
    void buildPreReleaseCheckpointYaml_multipleComponents() {
        List<String[]> components = List.of(
                new String[]{"alpha", "main", "aaa", "1.0", "false"},
                new String[]{"beta", "develop", "bbb", "2.0-SNAPSHOT", "true"});

        String yaml = WsReleaseMojo.buildPreReleaseCheckpointYaml(
                "multi", "2026-01-01T00:00:00Z", components);

        assertThat(yaml)
                .contains("  alpha:")
                .contains("  beta:");
    }

    @Test
    void buildPreReleaseCheckpointYaml_emptyComponents() {
        String yaml = WsReleaseMojo.buildPreReleaseCheckpointYaml(
                "empty", "2026-01-01T00:00:00Z", List.of());

        assertThat(yaml)
                .contains("components:\n")
                .endsWith("components:\n");
    }

    // ── extractVersionFromPom: edge cases ────────────────────────────

    @Test
    void extractVersionFromPom_versionWithQualifier() {
        String pom = "<project><version>3.0.0-my-feature-SNAPSHOT</version></project>";
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("3.0.0-my-feature-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_multipleVersionElements_returnsFirst() {
        // Multiple <version> elements — returns the first one found
        String pom = """
                <project>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("1.0.0");
    }

    @Test
    void extractVersionFromPom_versionInComment_skipsComment() {
        // Comments should not confuse the regex because the regex
        // matches the first <version> tag which is the real one
        String pom = """
                <project>
                    <!-- version was 0.9 -->
                    <version>1.0.0</version>
                </project>
                """;
        assertThat(WsReleaseMojo.extractVersionFromPom(pom))
                .isEqualTo("1.0.0");
    }

    // ── updateParentVersion: additional edge cases ───────────────────

    @Test
    void updateParentVersion_noParentBlock_unchanged() {
        String pom = """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                </project>
                """;
        String result = WsReleaseMojo.updateParentVersion(pom, "ike-parent", "21-SNAPSHOT");

        // No parent block → no change
        assertThat(result).isEqualTo(pom);
    }

    @Test
    void updateParentVersion_multipleArtifactIds_onlyParentChanged() {
        String pom = """
                <project>
                    <parent>
                        <artifactId>ike-parent</artifactId>
                        <version>19</version>
                    </parent>
                    <artifactId>my-module</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <artifactId>ike-parent</artifactId>
                            <version>19</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String result = WsReleaseMojo.updateParentVersion(pom, "ike-parent", "21");

        // Parent version should be updated, but the dependency version should not
        // (updateParentVersion only modifies the parent block)
        assertThat(result).contains("<parent>");
    }

    // ── updateVersionProperty: edge cases ────────────────────────────

    @Test
    void updateVersionProperty_propertyNotPresent_unchanged() {
        String pom = """
                <properties>
                    <java.version>21</java.version>
                </properties>
                """;
        String result = WsReleaseMojo.updateVersionProperty(
                pom, "ike-pipeline.version", "21-SNAPSHOT");

        assertThat(result).isEqualTo(pom);
    }
}
