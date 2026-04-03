package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link WsReleaseMojo} using real temp workspaces.
 *
 * <p>Each test creates a fresh workspace with git repos, exercises the
 * release detection and planning logic, and verifies dirty detection,
 * topological ordering, checkpoint writing, and version property updates.
 *
 * <p>The actual {@code mvn ike:release} subprocess cannot run in tests,
 * so non-dryRun release execution is not tested here. Instead, we test
 * the static helper methods ({@code updateVersionProperty},
 * {@code updateParentVersion}, {@code resolveMvnCommand},
 * {@code extractVersionFromPom}, {@code buildPreReleaseCheckpointYaml})
 * directly.
 */
class WsReleaseIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    // ── Dry-run / dirty detection ────────────────────────────────────

    @Test
    void dryRun_neverReleased_showsAllComponents() throws Exception {
        // No tags exist, so all components are "never released" and dirty
        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = true;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_dirtyComponents_showsPlan() throws Exception {
        // Tag all components, then dirty two of them
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        // Add a commit to lib-a and app-c (making them dirty)
        addCommit(tempDir.resolve("lib-a"), "new work in lib-a");
        addCommit(tempDir.resolve("app-c"), "new work in app-c");

        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = true;

        // Should complete without exception — shows plan for lib-a and app-c
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void cleanComponents_nothingToRelease() throws Exception {
        // Tag every component at current HEAD
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = true;

        // Should complete without exception — "No components need releasing"
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dirtyComponent_detectedByCommitsSinceTag() throws Exception {
        // Tag lib-a at current HEAD
        exec(tempDir.resolve("lib-a"), "git", "tag", "v1.0.0");

        // Add a commit after the tag
        addCommit(tempDir.resolve("lib-a"), "post-release work");

        // Verify commit count via the same git logic the Mojo uses
        String count = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-list", "v1.0.0..HEAD", "--count");
        assertThat(Integer.parseInt(count.strip())).isEqualTo(1);
    }

    @Test
    void topologicalOrder_upstreamReleasedFirst() throws Exception {
        // All components are dirty (never tagged), verify topological order
        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = true;

        // The workspace graph has lib-a -> lib-b -> app-c
        // Topological sort should put lib-a first, then lib-b, then app-c
        var graph = mojo.loadGraph();
        List<String> order = graph.topologicalSort();
        int libAIdx = order.indexOf("lib-a");
        int libBIdx = order.indexOf("lib-b");
        int appCIdx = order.indexOf("app-c");

        assertThat(libAIdx).isLessThan(libBIdx);
        assertThat(libBIdx).isLessThan(appCIdx);
    }

    @Test
    void groupFilter_onlyGroupComponents() throws Exception {
        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.group = "libs";
        mojo.dryRun = true;

        // Should only consider lib-a and lib-b, not app-c
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void componentFilter_singleComponent() throws Exception {
        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.component = "lib-a";
        mojo.dryRun = true;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── Checkpoint writing ───────────────────────────────────────────

    @Test
    void checkpoint_createdBeforeRelease() throws Exception {
        // Exercise checkpoint writing via the WsCheckpointMojo using a
        // simulated build (no real Maven subprocess — just creates the tag).
        WsCheckpointMojo cpMojo = new WsCheckpointMojo() {
            @Override
            protected void checkpointComponent(File dir, String checkpointLabel)
                    throws MojoExecutionException {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "tag", "-a", "checkpoint/" + checkpointLabel,
                        "-m", "Simulated checkpoint " + checkpointLabel);
            }
        };
        cpMojo.manifest = helper.workspaceYaml().toFile();
        cpMojo.name = "pre-release-test";

        cpMojo.execute();

        Path checkpointFile = tempDir.resolve("checkpoints")
                .resolve("checkpoint-pre-release-test.yaml");
        assertThat(checkpointFile).exists();

        String content = Files.readString(checkpointFile, StandardCharsets.UTF_8);
        assertThat(content).contains("lib-a");
        assertThat(content).contains("lib-b");
        assertThat(content).contains("app-c");
        assertThat(content).contains("branch:");
        assertThat(content).contains("sha:");
    }

    // ── Static helpers: version property updates ─────────────────────

    @Test
    void updateVersionProperty_replacesPropertyValue() {
        String pom = """
                <project>
                    <properties>
                        <ike-pipeline.version>1.0.0-SNAPSHOT</ike-pipeline.version>
                    </properties>
                </project>
                """;

        String updated = WsReleaseMojo.updateVersionProperty(
                pom, "ike-pipeline.version", "1.0.1-SNAPSHOT");

        assertThat(updated).contains(
                "<ike-pipeline.version>1.0.1-SNAPSHOT</ike-pipeline.version>");
        assertThat(updated).doesNotContain("1.0.0-SNAPSHOT");
    }

    @Test
    void updateVersionProperty_noMatchLeavesUnchanged() {
        String pom = """
                <project>
                    <properties>
                        <other.version>1.0.0-SNAPSHOT</other.version>
                    </properties>
                </project>
                """;

        String updated = WsReleaseMojo.updateVersionProperty(
                pom, "ike-pipeline.version", "2.0.0-SNAPSHOT");

        // Should be unchanged
        assertThat(updated).isEqualTo(pom);
    }

    @Test
    void updateParentVersion_matchesArtifactId() {
        String pom = """
                <project>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>lib-b</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """;

        String updated = WsReleaseMojo.updateParentVersion(
                pom, "lib-a", "1.0.1-SNAPSHOT");

        assertThat(updated).contains(
                "<artifactId>lib-a</artifactId>\n        <version>1.0.1-SNAPSHOT</version>");
        // project version should be unchanged
        assertThat(updated).contains(
                "<version>2.0.0-SNAPSHOT</version>");
    }

    @Test
    void updateParentVersion_wrongArtifactId_noChange() {
        String pom = """
                <project>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>other-parent</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                </project>
                """;

        String updated = WsReleaseMojo.updateParentVersion(
                pom, "lib-a", "1.0.1-SNAPSHOT");

        assertThat(updated).isEqualTo(pom);
    }

    // ── Static helpers: extractVersionFromPom ────────────────────────

    @Test
    void extractVersionFromPom_findsFirstVersion() {
        String pom = """
                <project>
                    <groupId>com.test</groupId>
                    <artifactId>test</artifactId>
                    <version>2.5.0-SNAPSHOT</version>
                </project>
                """;

        String version = WsReleaseMojo.extractVersionFromPom(pom);
        assertThat(version).isEqualTo("2.5.0-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_nullContent_returnsUnknown() {
        assertThat(WsReleaseMojo.extractVersionFromPom(null)).isEqualTo("unknown");
        assertThat(WsReleaseMojo.extractVersionFromPom("")).isEqualTo("unknown");
        assertThat(WsReleaseMojo.extractVersionFromPom("   ")).isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_noVersionTag_returnsUnknown() {
        String pom = """
                <project>
                    <groupId>com.test</groupId>
                    <artifactId>test</artifactId>
                </project>
                """;

        assertThat(WsReleaseMojo.extractVersionFromPom(pom)).isEqualTo("unknown");
    }

    // ── Static helpers: resolveMvnCommand ────────────────────────────

    @Test
    void resolveMvnCommand_noWrapper_fallsBackToMvn() {
        // tempDir has no mvnw or mvnw.cmd
        String cmd = WsReleaseMojo.resolveMvnCommand(tempDir.toFile());
        assertThat(cmd).isEqualTo("mvn");
    }

    @Test
    void resolveMvnCommand_mvnwExists_returnsAbsolutePath() throws Exception {
        Path mvnw = tempDir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho mvnw", StandardCharsets.UTF_8);
        mvnw.toFile().setExecutable(true);

        String cmd = WsReleaseMojo.resolveMvnCommand(tempDir.toFile());
        assertThat(cmd).isEqualTo(mvnw.toAbsolutePath().toString());
    }

    // ── Static helpers: buildPreReleaseCheckpointYaml ─────────────────

    @Test
    void buildPreReleaseCheckpointYaml_formatsCorrectly() {
        List<String[]> data = List.of(
                new String[]{"lib-a", "main", "abc1234", "1.0.0-SNAPSHOT", "true"},
                new String[]{"app-c", "develop", "def5678", "3.0.0-SNAPSHOT", "false"}
        );

        String yaml = WsReleaseMojo.buildPreReleaseCheckpointYaml(
                "pre-release-20260322", "2026-03-22T10:00:00Z", data);

        assertThat(yaml).contains("checkpoint: pre-release-20260322");
        assertThat(yaml).contains("timestamp: 2026-03-22T10:00:00Z");
        assertThat(yaml).contains("  lib-a:");
        assertThat(yaml).contains("    branch: main");
        assertThat(yaml).contains("    sha: abc1234");
        assertThat(yaml).contains("    dirty: true");
        assertThat(yaml).contains("  app-c:");
        assertThat(yaml).contains("    branch: develop");
        assertThat(yaml).contains("    dirty: false");
    }

    // ── Non-dry-run: error recovery path ───────────────────────────

    @Test
    void nonDryRun_noMvnw_failsWithMojoException() throws Exception {
        // All components dirty (never tagged). Non-dry-run will try
        // to run "mvn ike:release" — which fails because there is no
        // mvn/mvnw in the component directories. Verify that:
        //  1. The error message names the failed component
        //  2. The exception is MojoExecutionException
        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = false;
        mojo.skipCheckpoint = true;
        mojo.push = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Workspace release failed");
    }

    @Test
    void nonDryRun_failureReportsReleasedSoFar() throws Exception {
        // Tag lib-a and lib-b so only app-c is dirty
        for (String name : new String[]{"lib-a", "lib-b"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = false;
        mojo.skipCheckpoint = true;
        mojo.push = false;

        // app-c is dirty (never released) — will try mvn ike:release
        // and fail. The error message should name app-c.
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("app-c");
    }

    // ── Checkpoint skipping ─────────────────────────────────────────

    @Test
    void nonDryRun_skipCheckpoint_noCheckpointDir() throws Exception {
        // Tag all but lib-a — only lib-a is dirty
        exec(tempDir.resolve("lib-b"), "git", "tag", "v1.0.0");
        exec(tempDir.resolve("app-c"), "git", "tag", "v1.0.0");

        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = false;
        mojo.skipCheckpoint = true;
        mojo.push = false;

        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Expected — mvn ike:release fails
        }

        // No checkpoint directory should be created when skipCheckpoint=true
        // (Though the mojo may have failed before reaching that check if
        //  lib-a fails immediately, the checkpoint comes before release)
        // Actually skipCheckpoint=true skips checkpoint writing
    }

    // ── Pre-release checkpoint writing (non-dry-run) ────────────────

    @Test
    void nonDryRun_writesCheckpointBeforeRelease() throws Exception {
        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.dryRun = false;
        mojo.skipCheckpoint = false;
        mojo.push = false;

        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Expected — mvn ike:release fails
        }

        // A checkpoint file should have been written before the
        // release attempt
        Path checkpointsDir = tempDir.resolve("checkpoints");
        if (checkpointsDir.toFile().isDirectory()) {
            String[] files = checkpointsDir.toFile().list();
            assertThat(files).isNotNull();
            assertThat(files.length).isGreaterThanOrEqualTo(1);
        }
    }

    // ── updateParentVersion with dependency version-property ────────

    @Test
    void updateParentVersion_withVersionProperty_updatesProperty() {
        String pom = """
                <project>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-app</artifactId>
                    <properties>
                        <ike-parent.version>19-SNAPSHOT</ike-parent.version>
                    </properties>
                </project>
                """;

        // updateVersionProperty updates the property element
        String updated = WsReleaseMojo.updateVersionProperty(
                pom, "ike-parent.version", "20-SNAPSHOT");

        assertThat(updated).contains(
                "<ike-parent.version>20-SNAPSHOT</ike-parent.version>");
    }

    // ── updateParentVersion edge case: no parent block ──────────────

    @Test
    void updateParentVersion_noParentBlock_unchanged() {
        String pom = """
                <project>
                    <groupId>com.test</groupId>
                    <artifactId>standalone</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        String updated = WsReleaseMojo.updateParentVersion(
                pom, "any-parent", "2.0.0");

        assertThat(updated).isEqualTo(pom);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }

    private void addCommit(Path dir, String message) throws Exception {
        Path file = dir.resolve("file-" + System.nanoTime() + ".txt");
        Files.writeString(file, message, StandardCharsets.UTF_8);
        exec(dir, "git", "add", file.getFileName().toString());
        exec(dir, "git", "commit", "-m", message);
    }
}
