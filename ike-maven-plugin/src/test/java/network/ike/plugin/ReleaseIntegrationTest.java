package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ReleaseMojo} using real temp git repos.
 *
 * <p>Each test creates a release-ready project in a temporary directory,
 * configures the Mojo fields directly (package-private access), and
 * exercises the release workflow. External phases (Nexus, SSH, GitHub)
 * are expected to fail — tests verify local git state only.
 */
class ReleaseIntegrationTest {

    @TempDir
    Path tempDir;

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    void release_dryRun_noGitChanges() throws Exception {
        createReleaseProject(tempDir);

        String pomBefore = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String tagsBefore = execCapture(tempDir, "git", "tag", "-l");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.dryRun = true;
        mojo.skipVerify = true;
        mojo.deploySite = false;

        mojo.execute();

        // No tags created
        String tagsAfter = execCapture(tempDir, "git", "tag", "-l");
        assertThat(tagsAfter).isEqualTo(tagsBefore);

        // No new commits
        String headAfter = execCapture(tempDir, "git", "rev-parse", "HEAD");
        assertThat(headAfter).isEqualTo(headBefore);

        // POM unchanged
        String pomAfter = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomAfter).isEqualTo(pomBefore);
    }

    @Test
    void release_wrongBranch_throws() throws Exception {
        createReleaseProject(tempDir);
        exec(tempDir, "git", "checkout", "-b", "develop");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.deploySite = false;
        mojo.skipVerify = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("main");
    }

    @Test
    void release_allowBranch_overridesCheck() throws Exception {
        createReleaseProject(tempDir);
        exec(tempDir, "git", "checkout", "-b", "develop");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.allowBranch = "develop";
        mojo.skipVerify = true;
        mojo.deploySite = false;

        // The branch check should pass. The mojo will proceed into
        // the release workflow and may fail later (e.g., no remote,
        // no Maven wrapper). We just verify the branch check passed
        // by confirming the exception is NOT about the branch.
        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Acceptable — but must NOT be a branch check failure
            assertThat(e.getMessage()).doesNotContain("Must be on 'develop' branch");
        }
    }

    @Test
    void release_localPhase_createsTagAndBumps() throws Exception {
        createReleaseProject(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.skipVerify = true;
        mojo.deploySite = false;

        // The local phase should complete: branch, version, tag, merge, bump.
        // The external phase will fail (no remote, no Nexus). Catch and
        // verify git state regardless.
        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Expected — external phase failure
        }

        // Tag v1.0.0 should exist
        String tags = execCapture(tempDir, "git", "tag", "-l", "v1.0.0");
        assertThat(tags.strip()).isEqualTo("v1.0.0");

        // Should be on main branch
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch.strip()).isEqualTo("main");

        // POM version should be bumped to 1.0.1-SNAPSHOT
        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).contains("<version>1.0.1-SNAPSHOT</version>");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Create a release-ready project: git repo on main with a clean
     * worktree and a POM at version 1.0.0-SNAPSHOT.
     */
    private void createReleaseProject(Path dir) throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
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
