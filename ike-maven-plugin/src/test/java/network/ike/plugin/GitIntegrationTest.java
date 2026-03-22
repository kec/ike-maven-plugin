package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for git utility methods using real temp git repos.
 *
 * <p>No mocks — each test creates a temporary git repository, performs
 * real git operations, and verifies the utility methods against actual
 * git state. Tests run against whatever git version is installed.
 */
class GitIntegrationTest {

    @TempDir
    Path tempDir;

    private File repoDir;

    @BeforeEach
    void initRepo() throws Exception {
        repoDir = tempDir.toFile();
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        // Create initial commit so HEAD exists
        Files.writeString(tempDir.resolve("README.md"), "# Test\n");
        git("add", "README.md");
        git("commit", "-m", "initial commit");
    }

    // ── gitStatus ────────────────────────────────────────────────────

    @Test
    void gitStatus_cleanRepo_returnsEmpty() {
        String status = captureStatus();
        assertThat(status).isEmpty();
    }

    @Test
    void gitStatus_modifiedFile_returnsOutput() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# Modified\n");
        String status = captureStatus();
        assertThat(status).contains("README.md");
    }

    @Test
    void gitStatus_untrackedFile_returnsOutput() throws IOException {
        Files.writeString(tempDir.resolve("new-file.txt"), "content");
        String status = captureStatus();
        assertThat(status).contains("new-file.txt");
    }

    @Test
    void gitStatus_stagedFile_returnsOutput() throws IOException {
        Files.writeString(tempDir.resolve("staged.txt"), "content");
        git("add", "staged.txt");
        String status = captureStatus();
        assertThat(status).contains("staged.txt");
    }

    // ── gitBranch / currentBranch ────────────────────────────────────

    @Test
    void currentBranch_defaultBranch() throws MojoExecutionException {
        String branch = ReleaseSupport.currentBranch(repoDir);
        // Git default branch could be main or master depending on config
        assertThat(branch).matches("main|master");
    }

    @Test
    void currentBranch_featureBranch() throws Exception {
        git("checkout", "-b", "feature/my-work");
        String branch = ReleaseSupport.currentBranch(repoDir);
        assertThat(branch).isEqualTo("feature/my-work");
    }

    @Test
    void currentBranch_simpleBranch() throws Exception {
        git("checkout", "-b", "develop");
        String branch = ReleaseSupport.currentBranch(repoDir);
        assertThat(branch).isEqualTo("develop");
    }

    // ── requireCleanWorktree ─────────────────────────────────────────

    @Test
    void requireCleanWorktree_clean_noException() {
        assertThatCode(() -> ReleaseSupport.requireCleanWorktree(repoDir))
                .doesNotThrowAnyException();
    }

    @Test
    void requireCleanWorktree_unstagedChanges_throws() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# Dirty\n");
        assertThatThrownBy(() -> ReleaseSupport.requireCleanWorktree(repoDir))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("unstaged");
    }

    @Test
    void requireCleanWorktree_stagedChanges_throws() throws Exception {
        Files.writeString(tempDir.resolve("new.txt"), "staged content");
        git("add", "new.txt");
        assertThatThrownBy(() -> ReleaseSupport.requireCleanWorktree(repoDir))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("staged");
    }

    // ── hasRemote ────────────────────────────────────────────────────

    @Test
    void hasRemote_noRemotes_returnsFalse() {
        assertThat(ReleaseSupport.hasRemote(repoDir, "origin")).isFalse();
    }

    @Test
    void hasRemote_originExists_returnsTrue() throws Exception {
        git("remote", "add", "origin", "https://example.com/repo.git");
        assertThat(ReleaseSupport.hasRemote(repoDir, "origin")).isTrue();
    }

    @Test
    void hasRemote_wrongName_returnsFalse() throws Exception {
        git("remote", "add", "origin", "https://example.com/repo.git");
        assertThat(ReleaseSupport.hasRemote(repoDir, "upstream")).isFalse();
    }

    @Test
    void hasRemote_multipleRemotes() throws Exception {
        git("remote", "add", "origin", "https://example.com/repo.git");
        git("remote", "add", "upstream", "https://example.com/upstream.git");
        assertThat(ReleaseSupport.hasRemote(repoDir, "origin")).isTrue();
        assertThat(ReleaseSupport.hasRemote(repoDir, "upstream")).isTrue();
        assertThat(ReleaseSupport.hasRemote(repoDir, "other")).isFalse();
    }

    // ── Release tag detection (WsReleaseMojo helpers) ────────────────

    @Test
    void latestReleaseTag_noTags_returnsNull() {
        String tag = latestReleaseTag();
        assertThat(tag).isNull();
    }

    @Test
    void latestReleaseTag_singleTag_returnsThat() throws Exception {
        git("tag", "v1.0.0");
        String tag = latestReleaseTag();
        assertThat(tag).isEqualTo("v1.0.0");
    }

    @Test
    void latestReleaseTag_multipleTags_returnsLatest() throws Exception {
        git("tag", "v1.0.0");
        addCommit("second");
        git("tag", "v2.0.0");
        addCommit("third");
        git("tag", "v3.0.0");
        String tag = latestReleaseTag();
        assertThat(tag).isEqualTo("v3.0.0");
    }

    @Test
    void latestReleaseTag_ignoresNonReleaseTags() throws Exception {
        git("tag", "v1.0.0");
        addCommit("second");
        git("tag", "checkpoint/sprint-1");
        String tag = latestReleaseTag();
        assertThat(tag).isEqualTo("v1.0.0");
    }

    @Test
    void commitsSinceTag_noCommitsSince_returnsZero() throws Exception {
        git("tag", "v1.0.0");
        int count = commitsSinceTag("v1.0.0");
        assertThat(count).isZero();
    }

    @Test
    void commitsSinceTag_threeCommitsSince_returnsThree() throws Exception {
        git("tag", "v1.0.0");
        addCommit("one");
        addCommit("two");
        addCommit("three");
        int count = commitsSinceTag("v1.0.0");
        assertThat(count).isEqualTo(3);
    }

    @Test
    void commitsSinceTag_nonexistentTag_returnsNegative() {
        int count = commitsSinceTag("v99.99.99");
        assertThat(count).isEqualTo(-1);
    }

    // ── Syncthing-aware init (using local bare repo) ─────────────────

    @Test
    void syncthingInit_existingDirGetsGitInit() throws Exception {
        // Create a "bare" upstream repo
        Path upstream = tempDir.resolve("upstream.git");
        Files.createDirectories(upstream);
        execIn(upstream.toFile(), "git", "init", "--bare");

        // Push main to upstream
        git("remote", "add", "origin", upstream.toAbsolutePath().toString());
        git("push", "-u", "origin", "main");
        git("remote", "remove", "origin");

        // Create a Syncthing-style directory (files exist, no .git)
        Path syncDir = tempDir.resolve("syncthing-component");
        Files.createDirectories(syncDir);
        Files.writeString(syncDir.resolve("pom.xml"), "<project/>");
        Files.writeString(syncDir.resolve("src.java"), "class Src {}");

        // Simulate what InitWorkspaceMojo.initSyncthingRepo does
        File dir = syncDir.toFile();
        execIn(dir, "git", "init");
        execIn(dir, "git", "remote", "add", "origin",
                upstream.toAbsolutePath().toString());
        execIn(dir, "git", "fetch", "origin", "main");
        execIn(dir, "git", "reset", "origin/main");

        // Verify: git repo exists, files preserved, HEAD matches upstream
        assertThat(new File(dir, ".git")).isDirectory();
        assertThat(Files.readString(syncDir.resolve("pom.xml")))
                .isEqualTo("<project/>");
        String branch = ReleaseSupport.execCapture(dir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch.strip()).isEqualTo("main");
    }

    // ── Feature branch workflow ──────────────────────────────────────

    @Test
    void featureBranch_createAndSwitch() throws Exception {
        git("checkout", "-b", "feature/test-work");
        String branch = ReleaseSupport.currentBranch(repoDir);
        assertThat(branch).isEqualTo("feature/test-work");

        // Verify original branch still exists
        String branches = ReleaseSupport.execCapture(repoDir, "git", "branch");
        assertThat(branches).contains("main").contains("feature/test-work");
    }

    @Test
    void featureBranch_mergeNoFf() throws Exception {
        // Create feature branch with a commit
        git("checkout", "-b", "feature/merge-test");
        addCommit("feature work");

        // Merge back to main with --no-ff
        git("checkout", "main");
        git("merge", "--no-ff", "feature/merge-test", "-m", "Merge feature");

        // Verify merge commit exists (not fast-forward)
        String log = ReleaseSupport.execCapture(repoDir,
                "git", "log", "--oneline", "-3");
        assertThat(log).contains("Merge feature");
    }

    @Test
    void featureBranch_tagMergePoint() throws Exception {
        git("checkout", "-b", "feature/tag-test");
        addCommit("feature work");
        git("checkout", "main");
        git("merge", "--no-ff", "feature/tag-test", "-m", "Merge feature");
        git("tag", "merge/feature/tag-test/component");

        // Verify tag exists
        String tags = ReleaseSupport.execCapture(repoDir,
                "git", "tag", "-l", "merge/*");
        assertThat(tags.strip()).isEqualTo("merge/feature/tag-test/component");
    }

    // ── POM version read/write via ReleaseSupport ────────────────────

    @Test
    void readAndSetPomVersion() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0"?>
                <project>
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        File pomFile = pom.toFile();
        String version = ReleaseSupport.readPomVersion(pomFile);
        assertThat(version).isEqualTo("1.0.0-SNAPSHOT");

        ReleaseSupport.setPomVersion(pomFile, "1.0.0-SNAPSHOT", "1.0.0");
        String updated = ReleaseSupport.readPomVersion(pomFile);
        assertThat(updated).isEqualTo("1.0.0");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void git(String... args) throws RuntimeException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            execIn(repoDir, cmd);
        } catch (Exception e) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed", e);
        }
    }

    private void execIn(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes(); // consume output
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (exit " + exit + "): "
                    + String.join(" ", cmd));
        }
    }

    private void addCommit(String message) throws Exception {
        Path file = tempDir.resolve("file-" + System.nanoTime() + ".txt");
        Files.writeString(file, message);
        git("add", file.getFileName().toString());
        git("commit", "-m", message);
    }

    /** Mirrors WsReleaseMojo.latestReleaseTag() logic */
    private String latestReleaseTag() {
        try {
            String tags = ReleaseSupport.execCapture(repoDir,
                    "git", "tag", "-l", "v*", "--sort=-version:refname");
            if (tags == null || tags.isBlank()) return null;
            return tags.lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Mirrors WsReleaseMojo.commitsSinceTag() logic */
    private int commitsSinceTag(String tag) {
        try {
            String count = ReleaseSupport.execCapture(repoDir,
                    "git", "rev-list", tag + "..HEAD", "--count");
            return Integer.parseInt(count.strip());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Mirrors AbstractWorkspaceMojo.gitStatus() */
    private String captureStatus() {
        try {
            return ReleaseSupport.execCapture(repoDir,
                    "git", "status", "--porcelain");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
