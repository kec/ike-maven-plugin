package network.ike.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link CheckBranchMojo} using real temp workspaces.
 *
 * <p>Each test creates a workspace with git repos, sets {@code user.dir}
 * to simulate running from within a component, and verifies the Mojo's
 * warning behavior based on branch state.
 *
 * <p>CheckBranchMojo never throws — it only logs warnings. Tests verify
 * the Mojo completes without exception and use
 * {@link CheckBranchMojo#findComponentName} to validate component
 * resolution logic.
 */
class CheckBranchIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void onExpectedBranch_noException() throws Exception {
        // lib-a is on main, workspace.yaml expects main
        System.setProperty("user.dir",
                tempDir.resolve("lib-a").toAbsolutePath().toString());

        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        // Should complete silently — on expected branch
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void onWrongBranch_completesWithoutException() throws Exception {
        // Switch lib-a to an unexpected branch
        Path libA = tempDir.resolve("lib-a");
        exec(libA, "git", "checkout", "-b", "develop");

        System.setProperty("user.dir", libA.toAbsolutePath().toString());

        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        // CheckBranchMojo never throws — only warns
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void featureBranch_completesWithoutException() throws Exception {
        // Create a feature branch directly (not via ike:feature-start)
        Path libA = tempDir.resolve("lib-a");
        exec(libA, "git", "checkout", "-b", "feature/my-feature");

        System.setProperty("user.dir", libA.toAbsolutePath().toString());

        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        // Should complete without exception — warns about direct branch creation
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void noWorkspaceYaml_silentExit() throws Exception {
        // Create a bare git repo with no workspace.yaml anywhere
        Path bareDir = Files.createTempDirectory(tempDir, "bare-");
        Files.writeString(bareDir.resolve("pom.xml"), "<project/>",
                StandardCharsets.UTF_8);
        exec(bareDir, "git", "init", "-b", "main");
        exec(bareDir, "git", "config", "user.email", "test@example.com");
        exec(bareDir, "git", "config", "user.name", "Test");
        exec(bareDir, "git", "add", ".");
        exec(bareDir, "git", "commit", "-m", "init");

        System.setProperty("user.dir", bareDir.toAbsolutePath().toString());

        CheckBranchMojo mojo = new CheckBranchMojo();
        // Don't set manifest — let it search (and not find) workspace.yaml

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void outsideAnyComponent_silentExit() throws Exception {
        // Set user.dir to workspace root (not inside any component)
        System.setProperty("user.dir",
                tempDir.toAbsolutePath().toString());

        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        // Should complete silently — not inside a known component
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── findComponentName tests ──────────────────────────────────────

    @Test
    void findComponentName_directComponentDir() throws Exception {
        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        var graph = mojo.loadGraph();

        String result = CheckBranchMojo.findComponentName(
                tempDir.toFile(),
                tempDir.resolve("lib-a").toFile(),
                graph);
        assertThat(result).isEqualTo("lib-a");
    }

    @Test
    void findComponentName_subdirectoryOfComponent() throws Exception {
        // Create a subdirectory inside lib-a
        Path subDir = tempDir.resolve("lib-a").resolve("src").resolve("main");
        Files.createDirectories(subDir);

        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        var graph = mojo.loadGraph();

        String result = CheckBranchMojo.findComponentName(
                tempDir.toFile(),
                subDir.toFile(),
                graph);
        assertThat(result).isEqualTo("lib-a");
    }

    @Test
    void findComponentName_workspaceRoot_returnsNull() throws Exception {
        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        var graph = mojo.loadGraph();

        String result = CheckBranchMojo.findComponentName(
                tempDir.toFile(),
                tempDir.toFile(),
                graph);
        assertThat(result).isNull();
    }

    @Test
    void findComponentName_unknownDir_returnsNull() throws Exception {
        Path unknownDir = tempDir.resolve("not-a-component");
        Files.createDirectories(unknownDir);

        CheckBranchMojo mojo = new CheckBranchMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        var graph = mojo.loadGraph();

        String result = CheckBranchMojo.findComponentName(
                tempDir.toFile(),
                unknownDir.toFile(),
                graph);
        assertThat(result).isNull();
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
