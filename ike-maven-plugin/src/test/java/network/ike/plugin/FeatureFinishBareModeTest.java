package network.ike.plugin;

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
 * Integration tests for {@link FeatureFinishMojo} bare-mode (no workspace.yaml).
 *
 * <p>Each test creates a standalone git repo, simulates a feature-start
 * state (feature branch with branch-qualified version), then exercises
 * the bare-mode feature-finish workflow.
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

        // Create a standalone git repo on main with pom.xml
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

        // Create feature branch with branch-qualified version
        exec(tempDir, "git", "checkout", "-b", BRANCH_NAME);

        // Qualify the version to simulate feature-start
        Path pom = tempDir.resolve("pom.xml");
        String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
        String qualified = pomContent.replace(
                "<version>3.0.0-SNAPSHOT</version>",
                "<version>3.0.0-test-finish-SNAPSHOT</version>");
        Files.writeString(pom, qualified, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m",
                "feature: set branch-qualified version");

        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void bareMode_mergesFeatureToMain() throws Exception {
        FeatureFinishMojo mojo = new FeatureFinishMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.push = false;
        mojo.dryRun = false;

        mojo.execute();

        // Verify on main branch
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("main");

        // Verify merge commit exists
        String log = execCapture(tempDir,
                "git", "log", "--oneline", "-5");
        assertThat(log).contains("Merge " + BRANCH_NAME);

        // Verify version stripped back to plain SNAPSHOT
        String pomContent = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains("3.0.0-SNAPSHOT");
    }

    @Test
    void bareMode_dryRun_staysOnFeatureBranch() throws Exception {
        FeatureFinishMojo mojo = new FeatureFinishMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.push = false;
        mojo.dryRun = true;

        mojo.execute();

        // Verify still on feature branch
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo(BRANCH_NAME);

        // Verify POM still has branch-qualified version
        String pomContent = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).contains("test-finish");
    }

    @Test
    void bareMode_wrongBranch_fails() throws Exception {
        // Checkout main so we're NOT on the feature branch
        exec(tempDir, "git", "checkout", "main");

        FeatureFinishMojo mojo = new FeatureFinishMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.push = false;
        mojo.dryRun = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Not on " + BRANCH_NAME);
    }

    @Test
    void bareMode_tagCreated() throws Exception {
        FeatureFinishMojo mojo = new FeatureFinishMojo();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.push = false;
        mojo.dryRun = false;

        mojo.execute();

        // Verify merge tag exists — format: merge/feature/<name>/<dir-name>
        String dirName = tempDir.getFileName().toString();
        String expectedTag = "merge/" + BRANCH_NAME + "/" + dirName;
        String tags = execCapture(tempDir,
                "git", "tag", "-l", "merge/*");
        assertThat(tags).contains(expectedTag);
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
}
