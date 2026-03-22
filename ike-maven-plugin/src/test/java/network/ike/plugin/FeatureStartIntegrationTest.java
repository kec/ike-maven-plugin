package network.ike.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link FeatureStartMojo} using real temp workspaces.
 *
 * <p>Each test creates a fresh workspace via {@link TestWorkspaceHelper},
 * configures the Mojo fields directly (package-private access), and
 * verifies git branch and POM version state after execution.
 */
class FeatureStartIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    @Test
    void featureStart_dryRun_noChanges() throws Exception {
        // Record initial state
        String libABranch = execCapture(tempDir.resolve("lib-a"), "git", "rev-parse", "--abbrev-ref", "HEAD");
        String libBBranch = execCapture(tempDir.resolve("lib-b"), "git", "rev-parse", "--abbrev-ref", "HEAD");
        String appCBranch = execCapture(tempDir.resolve("app-c"), "git", "rev-parse", "--abbrev-ref", "HEAD");

        FeatureStartMojo mojo = new FeatureStartMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "test-feature";
        mojo.dryRun = true;

        mojo.execute();

        // Verify no branches were created — all still on original branch
        assertThat(execCapture(tempDir.resolve("lib-a"), "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(libABranch);
        assertThat(execCapture(tempDir.resolve("lib-b"), "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(libBBranch);
        assertThat(execCapture(tempDir.resolve("app-c"), "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(appCBranch);

        // Verify no feature branch exists in any component
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branches = execCapture(tempDir.resolve(name), "git", "branch");
            assertThat(branches).doesNotContain("feature/test-feature");
        }
    }

    @Test
    void featureStart_createsBranchesAndQualifiesVersion() throws Exception {
        FeatureStartMojo mojo = new FeatureStartMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "my-feature";
        mojo.dryRun = false;

        mojo.execute();

        // Verify each component is on the feature branch
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/my-feature");
        }

        // Verify POM versions contain the feature qualifier
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String pomContent = Files.readString(
                    tempDir.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pomContent).contains("my-feature");
            assertThat(pomContent).contains("SNAPSHOT");
        }
    }

    @Test
    void featureStart_skipVersion_branchOnlyNoVersionChange() throws Exception {
        // Record original POM versions
        String libAPom = Files.readString(
                tempDir.resolve("lib-a").resolve("pom.xml"), StandardCharsets.UTF_8);
        String libBPom = Files.readString(
                tempDir.resolve("lib-b").resolve("pom.xml"), StandardCharsets.UTF_8);
        String appCPom = Files.readString(
                tempDir.resolve("app-c").resolve("pom.xml"), StandardCharsets.UTF_8);

        FeatureStartMojo mojo = new FeatureStartMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "skip-test";
        mojo.skipVersion = true;
        mojo.dryRun = false;

        mojo.execute();

        // Verify branches created
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/skip-test");
        }

        // Verify POM versions unchanged
        assertThat(Files.readString(tempDir.resolve("lib-a").resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(libAPom);
        assertThat(Files.readString(tempDir.resolve("lib-b").resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(libBPom);
        assertThat(Files.readString(tempDir.resolve("app-c").resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(appCPom);
    }

    @Test
    void featureStart_groupFilter() throws Exception {
        FeatureStartMojo mojo = new FeatureStartMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "libs-only";
        mojo.group = "libs";
        mojo.dryRun = false;

        mojo.execute();

        // lib-a and lib-b should be on feature branch
        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/libs-only");
        assertThat(execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/libs-only");

        // app-c should still be on main
        assertThat(execCapture(tempDir.resolve("app-c"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
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
