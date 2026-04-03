package network.ike.plugin.ws;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for feature-finish bare-mode (no workspace.yaml).
 *
 * <p>Tests all three strategies: squash, merge, and rebase.
 */
class FeatureFinishBareModeTest {

    private static final String FEATURE_NAME = "test-finish";
    private static final String BRANCH_NAME = "feature/" + FEATURE_NAME;

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-finish</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial commit");

        exec(tempDir, "git", "checkout", "-b", BRANCH_NAME);

        Path pom = tempDir.resolve("pom.xml");
        String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
        String qualified = pomContent.replace(
                "<version>3.0.0-SNAPSHOT</version>",
                "<version>3.0.0-test-finish-SNAPSHOT</version>");
        Files.writeString(pom, qualified, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m", "feature: set branch-qualified version");

        // Add a real code change on the feature branch (beyond just version)
        Files.writeString(tempDir.resolve("feature-work.txt"),
                "actual feature content", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "feature-work.txt");
        exec(tempDir, "git", "commit", "-m", "feature: add feature work");

        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    // ── Squash strategy tests ────────────────────────────────────

    @Test
    void squash_mergesAndStripsVersion() throws Exception {
        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash: add test feature";
        mojo.dryRun = false;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // Version stripped back to plain SNAPSHOT
        String pomContent = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains("3.0.0-SNAPSHOT");

        // Squash commit message present (not a merge commit)
        String log = execCapture(tempDir, "git", "log", "--oneline", "-3");
        assertThat(log).contains("Squash: add test feature");
        assertThat(log).doesNotContain("Merge ");
    }

    @Test
    void squash_deletesBranchByDefault() throws Exception {
        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash merge";
        mojo.dryRun = false;

        mojo.execute();

        // Feature branch should be deleted
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).doesNotContain("feature/test-finish");
    }

    @Test
    void squash_keepBranch_preservesBranch() throws Exception {
        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash merge";
        mojo.keepBranch = true;
        mojo.dryRun = false;

        mojo.execute();

        // Feature branch should still exist
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).contains("feature/test-finish");
    }

    @Test
    void squash_multiModule_stripsSubmoduleVersions() throws Exception {
        Path subDir = tempDir.resolve("sub-module");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>bare-finish</artifactId>
                        <version>3.0.0-test-finish-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-module</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Add submodule");

        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash with submodules";
        mojo.dryRun = false;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // Root POM stripped
        String rootPom = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(rootPom).doesNotContain("test-finish");
        assertThat(rootPom).contains("3.0.0-SNAPSHOT");

        // Submodule parent version stripped
        String subPom = Files.readString(subDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(subPom).doesNotContain("test-finish");
        assertThat(subPom).contains("3.0.0-SNAPSHOT");
    }

    // ── Merge strategy tests ─────────────────────────────────────

    @Test
    void merge_createsMergeCommit() throws Exception {
        FeatureFinishMergeMojo mojo = new FeatureFinishMergeMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.dryRun = false;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // Merge commit present
        String log = execCapture(tempDir, "git", "log", "--oneline", "-5");
        assertThat(log).contains("Merge " + BRANCH_NAME);
    }

    @Test
    void merge_keepsBranchByDefault() throws Exception {
        FeatureFinishMergeMojo mojo = new FeatureFinishMergeMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.dryRun = false;

        mojo.execute();

        // Branch kept by default for merge strategy
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).contains("feature/test-finish");
    }

    // ── Rebase strategy tests ────────────────────────────────────

    @Test
    void rebase_producesLinearHistory() throws Exception {
        FeatureFinishRebaseMojo mojo = new FeatureFinishRebaseMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.dryRun = false;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // No merge commits — linear history
        String log = execCapture(tempDir, "git", "log", "--oneline", "-5");
        assertThat(log).doesNotContain("Merge ");
    }

    // ── Common tests ─────────────────────────────────────────────

    @Test
    void dryRun_staysOnFeatureBranch() throws Exception {
        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should not happen";
        mojo.dryRun = true;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo(BRANCH_NAME);
        String pomContent = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).contains("test-finish");
    }

    @Test
    void wrongBranch_fails() throws Exception {
        exec(tempDir, "git", "checkout", "main");

        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should fail";
        mojo.dryRun = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Not on " + BRANCH_NAME);
    }

    // ── VCS state file tests ─────────────────────────────────────

    @Test
    void squash_writesVcsState() throws Exception {
        // Create .ike directory to enable VCS state
        Files.createDirectories(tempDir.resolve(".ike"));
        Files.writeString(tempDir.resolve(".ike/.gitkeep"), "", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", ".ike");
        exec(tempDir, "git", "commit", "-m", "Add .ike marker");

        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash with state";
        mojo.dryRun = false;

        mojo.execute();

        // VCS state file should exist with feature-finish action
        Path stateFile = tempDir.resolve(".ike/vcs-state");
        assertThat(stateFile).exists();
        String content = Files.readString(stateFile, StandardCharsets.UTF_8);
        assertThat(content).contains("action=feature-finish");
        assertThat(content).contains("branch=main");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String currentBranch() throws Exception {
        return execCapture(tempDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
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
