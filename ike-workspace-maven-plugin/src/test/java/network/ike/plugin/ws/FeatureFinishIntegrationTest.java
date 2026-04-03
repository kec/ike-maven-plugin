package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for feature-finish goals using real temp workspaces.
 *
 * <p>Tests squash-merge (default strategy) and no-ff merge across
 * workspace components.
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

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path compDir = tempDir.resolve(name);

            exec(compDir, "git", "checkout", "-b", BRANCH_NAME);

            Path pom = compDir.resolve("pom.xml");
            String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
            String qualified = pomContent.replaceFirst(
                    "<version>(\\d+\\.\\d+\\.\\d+)-SNAPSHOT</version>",
                    "<version>$1-test-finish-SNAPSHOT</version>");
            Files.writeString(pom, qualified, StandardCharsets.UTF_8);

            exec(compDir, "git", "add", "pom.xml");
            exec(compDir, "git", "commit", "-m",
                    "feature: set branch-qualified version");

            // Add actual feature work (beyond just version change)
            Files.writeString(compDir.resolve("feature-work.txt"),
                    "feature content for " + name, StandardCharsets.UTF_8);
            exec(compDir, "git", "add", "feature-work.txt");
            exec(compDir, "git", "commit", "-m", "feature: add feature work");
        }

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
    void squash_dryRun_noMerge() throws Exception {
        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should not happen";
        mojo.dryRun = true;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo(BRANCH_NAME);
        }
    }

    @Test
    void squash_mergesAndStripsVersions() throws Exception {
        FeatureFinishSquashMojo mojo = new FeatureFinishSquashMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash feature";
        mojo.dryRun = false;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }

        // Versions stripped
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String pomContent = Files.readString(
                    tempDir.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pomContent).doesNotContain("test-finish");
            assertThat(pomContent).contains("SNAPSHOT");
        }

        // Squash commits present (no "Merge" commits)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String log = execCapture(tempDir.resolve(name),
                    "git", "log", "--oneline", "-3");
            assertThat(log).contains("Squash feature");
        }
    }

    @Test
    void merge_preservesHistory() throws Exception {
        FeatureFinishMergeMojo mojo = new FeatureFinishMergeMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.dryRun = false;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }

        // Merge commits present
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String log = execCapture(tempDir.resolve(name),
                    "git", "log", "--oneline", "-5");
            assertThat(log).contains("Merge " + BRANCH_NAME);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

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
