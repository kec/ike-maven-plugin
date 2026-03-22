package network.ike.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link FeatureFinishMojo} using real temp workspaces.
 *
 * <p>Each test creates a fresh workspace via {@link TestWorkspaceHelper},
 * simulates a feature-start state (branch creation + version qualification),
 * then exercises the feature-finish workflow and verifies merge state.
 */
class FeatureFinishIntegrationTest {

    private static final String FEATURE_NAME = "test-finish";
    private static final String BRANCH_NAME = "feature/" + FEATURE_NAME;

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Simulate feature-start: create branch and qualify version in each component
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path compDir = tempDir.resolve(name);

            // Create feature branch
            exec(compDir, "git", "checkout", "-b", BRANCH_NAME);

            // Read current POM and add branch qualifier to version
            Path pom = compDir.resolve("pom.xml");
            String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
            // Replace e.g. 1.0.0-SNAPSHOT with 1.0.0-test-finish-SNAPSHOT
            String qualified = pomContent.replaceFirst(
                    "<version>(\\d+\\.\\d+\\.\\d+)-SNAPSHOT</version>",
                    "<version>$1-test-finish-SNAPSHOT</version>");
            Files.writeString(pom, qualified, StandardCharsets.UTF_8);

            // Commit the version change
            exec(compDir, "git", "add", "pom.xml");
            exec(compDir, "git", "commit", "-m",
                    "feature: set branch-qualified version");
        }

        // Update workspace.yaml so manifest versions match the branch-qualified POMs.
        // FeatureFinishMojo checks component.version() from the manifest to decide
        // whether to strip the branch qualifier.
        Path wsYaml = helper.workspaceYaml();
        String yaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        yaml = yaml.replace("version: \"1.0.0-SNAPSHOT\"",
                            "version: \"1.0.0-test-finish-SNAPSHOT\"");
        yaml = yaml.replace("version: \"2.0.0-SNAPSHOT\"",
                            "version: \"2.0.0-test-finish-SNAPSHOT\"");
        yaml = yaml.replace("version: \"3.0.0-SNAPSHOT\"",
                            "version: \"3.0.0-test-finish-SNAPSHOT\"");
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void featureFinish_dryRun_noMerge() throws Exception {
        FeatureFinishMojo mojo = new FeatureFinishMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.push = false;
        mojo.dryRun = true;

        mojo.execute();

        // Verify still on feature branch — no merge happened
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo(BRANCH_NAME);
        }

        // Verify no merge tags exist
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String tags = execCapture(tempDir.resolve(name), "git", "tag", "-l");
            assertThat(tags).doesNotContain("merge/");
        }
    }

    @Test
    void featureFinish_mergesAndTags() throws Exception {
        FeatureFinishMojo mojo = new FeatureFinishMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.push = false;
        mojo.dryRun = false;

        mojo.execute();

        // Verify each component is on main (target branch)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }

        // Verify merge tags exist for each component
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String expectedTag = "merge/" + BRANCH_NAME + "/" + name;
            String tags = execCapture(tempDir.resolve(name),
                    "git", "tag", "-l", expectedTag);
            assertThat(tags.strip()).isEqualTo(expectedTag);
        }

        // Verify merge commit exists in git log (--no-ff merge)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String log = execCapture(tempDir.resolve(name),
                    "git", "log", "--oneline", "-5");
            assertThat(log).contains("Merge " + BRANCH_NAME);
        }

        // Verify version qualifier stripped from POMs (back to plain SNAPSHOT)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String pomContent = Files.readString(
                    tempDir.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pomContent).doesNotContain("test-finish");
            assertThat(pomContent).contains("SNAPSHOT");
        }
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
