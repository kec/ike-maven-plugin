package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions in {@link WsCheckpointMojo}: YAML generation,
 * tag name derivation, file naming, and status formatting.
 */
class CheckpointSupportTest {

    @Test
    void buildCheckpointYaml_header_containsMetadata() {
        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "sprint-42", "2026-03-20T10:00:00Z", "kec", "1.0",
                List.of(), List.of());

        assertThat(yaml)
                .contains("name: \"sprint-42\"")
                .contains("created: \"2026-03-20T10:00:00Z\"")
                .contains("author: \"kec\"")
                .contains("schema-version: \"1.0\"")
                .contains("components:");
    }

    @Test
    void buildCheckpointYaml_singleComponent() {
        ComponentSnapshot snap = new ComponentSnapshot(
                "ike-pipeline", "abc123def456", "abc123d",
                "main", "20-SNAPSHOT", false,
                "infrastructure", false);

        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        assertThat(yaml)
                .contains("    ike-pipeline:")
                .contains("      sha: \"abc123def456\"")
                .contains("      short-sha: \"abc123d\"")
                .contains("      branch: \"main\"")
                .contains("      type: infrastructure")
                .contains("      version: \"20-SNAPSHOT\"")
                .doesNotContain("dirty: true");
    }

    @Test
    void buildCheckpointYaml_dirtyFlagDoesNotAppearInOutput() {
        // Checkpoints require a clean worktree; the dirty field in
        // ComponentSnapshot is informational only and is not written to YAML.
        ComponentSnapshot snap = new ComponentSnapshot(
                "ike-docs", "aaa", "aaa",
                "feature/docs", "1.0-SNAPSHOT", true,
                "document", false);

        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        assertThat(yaml)
                .contains("    ike-docs:")
                .contains("      sha: \"aaa\"")
                .doesNotContain("dirty:");
    }

    @Test
    void buildCheckpointYaml_compositeComponent_hasTodoComment() {
        ComponentSnapshot snap = new ComponentSnapshot(
                "tinkar-data", "bbb", "bbb",
                "main", "1.0", false,
                "knowledge-source", true);

        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        assertThat(yaml)
                .contains("# TODO: add view-coordinate from Tinkar runtime");
    }

    @Test
    void buildCheckpointYaml_absentComponent_markedAbsent() {
        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(), List.of("missing-repo"));

        assertThat(yaml)
                .contains("    missing-repo:")
                .contains("      status: absent");
    }

    @Test
    void buildCheckpointYaml_nullVersion_omitted() {
        ComponentSnapshot snap = new ComponentSnapshot(
                "no-pom", "ccc", "ccc",
                "main", null, false,
                "software", false);

        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        // The component section should not contain a version line
        // (schema-version in the header is separate)
        String componentSection = yaml.substring(yaml.indexOf("    no-pom:"));
        assertThat(componentSection)
                .doesNotContain("version:");
    }

    @Test
    void buildCheckpointYaml_emptyComponents_minimalOutput() {
        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "empty", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(), List.of());

        assertThat(yaml)
                .contains("checkpoint:")
                .contains("  components:")
                .endsWith("  components:\n");
    }

    @Test
    void buildCheckpointYaml_multipleComponents_allPresent() {
        List<ComponentSnapshot> snaps = List.of(
                new ComponentSnapshot("alpha", "a1", "a1", "main", "1.0", false, "software", false),
                new ComponentSnapshot("beta", "b2", "b2", "main", "2.0", false, "document", false));

        String yaml = WsCheckpointMojo.buildCheckpointYaml(
                "multi", "2026-01-01T00:00:00Z", "ci", "1.0",
                snaps, List.of());

        assertThat(yaml)
                .contains("    alpha:")
                .contains("    beta:");
    }

    // ── checkpointFileName ──────────────────────────────────────────

    @Test
    void checkpointFileName_standardFormat() {
        assertThat(WsCheckpointMojo.checkpointFileName("sprint-42"))
                .isEqualTo("checkpoint-sprint-42.yaml");
    }

    @Test
    void checkpointFileName_withTimestamp() {
        assertThat(WsCheckpointMojo.checkpointFileName("pre-release-20260320-100000"))
                .isEqualTo("checkpoint-pre-release-20260320-100000.yaml");
    }

}
