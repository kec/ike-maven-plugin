package network.ike.plugin;

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
 * Integration tests for workspace Mojo goals.
 *
 * <p>Each test creates a fresh temp workspace via {@link TestWorkspaceHelper},
 * then instantiates a Mojo directly, sets its {@code manifest} field
 * (package-private in {@link AbstractWorkspaceMojo}), and calls
 * {@link org.apache.maven.plugin.Mojo#execute()}.
 *
 * <p>These Mojos log output via {@code getLog().info()} which defaults
 * to {@code SystemStreamLog} — no mock is needed.
 */
class WorkspaceMojoIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    // ── VerifyWorkspaceMojo ─────────────────────────────────────────

    @Test
    void verify_validWorkspace_noException() {
        VerifyWorkspaceMojo mojo = new VerifyWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── CascadeWorkspaceMojo ────────────────────────────────────────

    @Test
    void cascade_fromLeaf_showsDownstream() {
        CascadeWorkspaceMojo mojo = new CascadeWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.component = "lib-a";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void cascade_fromTip_noDownstream() {
        CascadeWorkspaceMojo mojo = new CascadeWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.component = "app-c";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── GraphWorkspaceMojo ──────────────────────────────────────────

    @Test
    void graph_textFormat_runsSuccessfully() {
        GraphWorkspaceMojo mojo = new GraphWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.format = "text";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void graph_dotFormat_runsSuccessfully() {
        GraphWorkspaceMojo mojo = new GraphWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.format = "dot";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── StatusWorkspaceMojo ─────────────────────────────────────────

    @Test
    void status_allClean_runsSuccessfully() {
        StatusWorkspaceMojo mojo = new StatusWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void status_dirtyRepo_runsSuccessfully() throws Exception {
        // Dirty lib-a by adding an untracked file
        Path untracked = tempDir.resolve("lib-a").resolve("dirty.txt");
        Files.writeString(untracked, "uncommitted", StandardCharsets.UTF_8);

        StatusWorkspaceMojo mojo = new StatusWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void status_groupFilter_runsSuccessfully() {
        StatusWorkspaceMojo mojo = new StatusWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.group = "libs";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── DashboardWorkspaceMojo ─────────────────────────────────────

    @Test
    void dashboard_cleanWorkspace_succeeds() {
        DashboardWorkspaceMojo mojo = new DashboardWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dashboard_dirtyWorkspace_showsCascade() throws Exception {
        // Dirty lib-a by adding an untracked file
        Path untracked = tempDir.resolve("lib-a").resolve("dirty.txt");
        Files.writeString(untracked, "uncommitted", StandardCharsets.UTF_8);

        DashboardWorkspaceMojo mojo = new DashboardWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── InitWorkspaceMojo ────────────────────────────────────────────

    @Test
    void init_freshClone_clonesAllComponents() throws Exception {
        Path initRoot = Files.createTempDirectory(tempDir, "init-");
        TestWorkspaceHelper initHelper = new TestWorkspaceHelper(initRoot);
        initHelper.buildWorkspaceWithUpstreams();

        InitWorkspaceMojo mojo = new InitWorkspaceMojo();
        mojo.manifest = initHelper.workspaceYaml().toFile();

        mojo.execute();

        // All 3 components should be cloned
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path compDir = initRoot.resolve(name);
            assertThat(compDir).isDirectory();
            assertThat(compDir.resolve(".git")).isDirectory();
            assertThat(compDir.resolve("pom.xml")).isRegularFile();
        }
    }

    @Test
    void init_alreadyCloned_skips() throws Exception {
        Path initRoot = Files.createTempDirectory(tempDir, "init-");
        TestWorkspaceHelper initHelper = new TestWorkspaceHelper(initRoot);
        initHelper.buildWorkspaceWithUpstreams();

        // Pre-create lib-a as a git repo with one commit
        Path libA = initRoot.resolve("lib-a");
        Files.createDirectories(libA);
        Files.writeString(libA.resolve("pom.xml"), "<project/>",
                StandardCharsets.UTF_8);
        exec(libA, "git", "init", "-b", "main");
        exec(libA, "git", "config", "user.email", "test@example.com");
        exec(libA, "git", "config", "user.name", "Test");
        exec(libA, "git", "add", ".");
        exec(libA, "git", "commit", "-m", "pre-existing");

        // Count commits before init
        String countBefore = execCapture(libA, "git", "rev-list", "--count", "HEAD");

        InitWorkspaceMojo mojo = new InitWorkspaceMojo();
        mojo.manifest = initHelper.workspaceYaml().toFile();

        mojo.execute();

        // lib-a should not be re-cloned — commit count unchanged
        String countAfter = execCapture(libA, "git", "rev-list", "--count", "HEAD");
        assertThat(countAfter).isEqualTo(countBefore);

        // lib-b and app-c should have been cloned
        assertThat(initRoot.resolve("lib-b").resolve(".git")).isDirectory();
        assertThat(initRoot.resolve("app-c").resolve(".git")).isDirectory();
    }

    @Test
    void init_groupFilter_clonesOnlyGroup() throws Exception {
        Path initRoot = Files.createTempDirectory(tempDir, "init-");
        TestWorkspaceHelper initHelper = new TestWorkspaceHelper(initRoot);
        initHelper.buildWorkspaceWithUpstreams();

        InitWorkspaceMojo mojo = new InitWorkspaceMojo();
        mojo.manifest = initHelper.workspaceYaml().toFile();
        mojo.group = "libs";

        mojo.execute();

        // libs group: lib-a and lib-b should be cloned
        assertThat(initRoot.resolve("lib-a").resolve(".git")).isDirectory();
        assertThat(initRoot.resolve("lib-b").resolve(".git")).isDirectory();

        // app-c should NOT be cloned
        assertThat(initRoot.resolve("app-c")).doesNotExist();
    }

    // ── StignoreWorkspaceMojo ───────────────────────────────────────

    @Test
    void stignore_createsFiles() throws Exception {
        StignoreWorkspaceMojo mojo = new StignoreWorkspaceMojo();
        mojo.manifest = helper.workspaceYaml().toFile();

        mojo.execute();

        // Workspace-level .stignore
        Path wsStignore = tempDir.resolve(".stignore");
        assertThat(wsStignore).exists();
        String wsContent = Files.readString(wsStignore, StandardCharsets.UTF_8);
        assertThat(wsContent).contains("**/target");
        assertThat(wsContent).contains("**/.git");
        assertThat(wsContent).contains("checkpoints");

        // Per-component .stignore files
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path compStignore = tempDir.resolve(name).resolve(".stignore");
            assertThat(compStignore).exists();
            String compContent = Files.readString(compStignore,
                    StandardCharsets.UTF_8);
            assertThat(compContent).contains("**/target");
            assertThat(compContent).contains("**/.git");
        }
    }

    // ── WsCheckpointMojo ──────────────────────────────────────────────

    @Test
    void wsCheckpoint_writesYamlFile() throws Exception {
        WsCheckpointMojo mojo = new WsCheckpointMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.name = "test-cp";

        mojo.execute();

        Path checkpointFile = tempDir.resolve("checkpoints")
                .resolve("checkpoint-test-cp.yaml");
        assertThat(checkpointFile).exists();

        String content = Files.readString(checkpointFile, StandardCharsets.UTF_8);
        assertThat(content).contains("lib-a");
        assertThat(content).contains("lib-b");
        assertThat(content).contains("app-c");
    }

    @Test
    void wsCheckpoint_withTag_createsGitTags() throws Exception {
        WsCheckpointMojo mojo = new WsCheckpointMojo();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.name = "tagged-cp";
        mojo.tag = true;

        mojo.execute();

        // Verify git tags exist in each component
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String expectedTag = "checkpoint/tagged-cp/" + name;
            String tags = execCapture(tempDir.resolve(name),
                    "git", "tag", "-l", expectedTag);
            assertThat(tags.strip()).isEqualTo(expectedTag);
        }
    }

    // ── WsReleaseMojo ───────────────────────────────────────────────

    @Test
    void wsRelease_dryRun_showsPlan() throws Exception {
        // Build a workspace with upstreams so graph loads correctly
        Path releaseRoot = Files.createTempDirectory(tempDir, "release-");
        TestWorkspaceHelper releaseHelper = new TestWorkspaceHelper(releaseRoot);
        releaseHelper.buildWorkspace();

        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = releaseHelper.workspaceYaml().toFile();
        mojo.dryRun = true;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void wsRelease_noChanges_reportsClean() throws Exception {
        // Build workspace and tag every component as released
        Path releaseRoot = Files.createTempDirectory(tempDir, "release-clean-");
        TestWorkspaceHelper releaseHelper = new TestWorkspaceHelper(releaseRoot);
        releaseHelper.buildWorkspace();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(releaseRoot.resolve(name), "git", "tag", "v1.0.0");
        }

        WsReleaseMojo mojo = new WsReleaseMojo();
        mojo.manifest = releaseHelper.workspaceYaml().toFile();
        mojo.dryRun = true;

        // Should complete without exception — reports "No components need releasing"
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── IkeHelpMojo ─────────────────────────────────────────────────

    @Test
    void help_execute_printsGoals() {
        IkeHelpMojo mojo = new IkeHelpMojo();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
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
