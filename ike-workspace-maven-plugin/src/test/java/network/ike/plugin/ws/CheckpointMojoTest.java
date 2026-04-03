package network.ike.plugin.ws;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CheckpointSupport}.
 *
 * <p>The checkpoint workflow runs subprocesses ({@code mvn clean deploy})
 * that cannot run in unit tests. These tests exercise the dry-run paths
 * (which cover parameter derivation, audit logging, and all dry-run
 * branches) and early validation logic (clean worktree check).
 */
class CheckpointMojoTest {

    @TempDir
    Path tempDir;

    // ── Dry-run: all parameter combinations ─────────────────────────

    @Test
    void dryRun_completesWithoutChanges() throws Exception {
        createCheckpointProject(tempDir);

        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String tagsBefore = execCapture(tempDir, "git", "tag", "-l");
        String pomBefore = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                false, false, new SystemStreamLog()))
                .doesNotThrowAnyException();

        // No commits, tags, or POM changes
        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD"))
                .isEqualTo(headBefore);
        assertThat(execCapture(tempDir, "git", "tag", "-l"))
                .isEqualTo(tagsBefore);
        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .isEqualTo(pomBefore);
    }

    @Test
    void dryRun_withCustomLabel() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "custom-label-42",
                false, false, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRun_skipVerifyTrue() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                false, true, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteFalse() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                false, false, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteTrue() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                true, false, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteTrue_skipVerifyFalse() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                true, false, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteFalse_skipVerifyTrue() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                false, true, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRun_customLabel_deploySiteTrue() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.dryRun(
                tempDir.toFile(), "my-checkpoint",
                true, true, new SystemStreamLog()))
                .doesNotThrowAnyException();
    }

    // ── Non-dry-run validation ──────────────────────────────────────

    @Test
    void nonDryRun_dirtyWorktree_unstaged_throws() throws Exception {
        createCheckpointProjectWithTrackedFile(tempDir);
        // Modify a tracked file (untracked files are not caught by git diff)
        Files.writeString(tempDir.resolve("README.txt"), "modified content",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CheckpointSupport.checkpoint(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                false, false, new SystemStreamLog()))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("unstaged changes");
    }

    @Test
    void nonDryRun_dirtyWorktree_staged_throws() throws Exception {
        createCheckpointProject(tempDir);
        Files.writeString(tempDir.resolve("staged.txt"), "staged",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "staged.txt");

        assertThatThrownBy(() -> CheckpointSupport.checkpoint(
                tempDir.toFile(), "2.0.0-checkpoint.20260330.abc1234",
                false, false, new SystemStreamLog()))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("staged changes");
    }

    @Test
    void nonDryRun_cleanWorktree_proceedsToVersionSet() throws Exception {
        createCheckpointProject(tempDir);

        // No Maven wrapper available, so checkpoint will fail after
        // setting the version (when it tries to run mvnw). Verify
        // that it got past the worktree check by observing the error
        // is about mvn/mvnw, not about worktree state.
        try {
            CheckpointSupport.checkpoint(tempDir.toFile(),
                    "2.0.0-checkpoint.20260330.abc1234",
                    false, false, new SystemStreamLog());
        } catch (MojoExecutionException e) {
            // Expected — no mvn/mvnw in temp dir
            // Should NOT be a worktree-related error
            assertThat(e.getMessage())
                    .doesNotContain("unstaged")
                    .doesNotContain("staged changes");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void createCheckpointProjectWithTrackedFile(Path dir) throws Exception {
        createCheckpointProject(dir);
        Files.writeString(dir.resolve("README.txt"), "readme content",
                StandardCharsets.UTF_8);
        exec(dir, "git", "add", "README.txt");
        exec(dir, "git", "commit", "-m", "Add README");
    }

    private void createCheckpointProject(Path dir) throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>checkpoint-project</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);

        exec(dir, "git", "init", "-b", "main");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

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
}
