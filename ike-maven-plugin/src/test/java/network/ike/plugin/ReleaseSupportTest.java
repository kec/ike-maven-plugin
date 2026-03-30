package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for pure functions in {@link ReleaseSupport}:
 * version derivation, path validation, site path generation,
 * branch-to-path conversion, POM reading, and POM writing.
 */
class ReleaseSupportTest {

    // ── deriveReleaseVersion ─────────────────────────────────────────

    @Test
    void deriveReleaseVersion_stripsSnapshot() {
        assertThat(ReleaseSupport.deriveReleaseVersion("2-SNAPSHOT"))
                .isEqualTo("2");
    }

    @Test
    void deriveReleaseVersion_dotted() {
        assertThat(ReleaseSupport.deriveReleaseVersion("1.1.0-SNAPSHOT"))
                .isEqualTo("1.1.0");
    }

    @Test
    void deriveReleaseVersion_noSnapshot_unchanged() {
        assertThat(ReleaseSupport.deriveReleaseVersion("3.0.0"))
                .isEqualTo("3.0.0");
    }

    // ── deriveNextSnapshot ───────────────────────────────────────────

    @Test
    void deriveNextSnapshot_simpleInteger() {
        assertThat(ReleaseSupport.deriveNextSnapshot("2"))
                .isEqualTo("3-SNAPSHOT");
    }

    @Test
    void deriveNextSnapshot_dotted() {
        assertThat(ReleaseSupport.deriveNextSnapshot("1.1.0"))
                .isEqualTo("1.1.1-SNAPSHOT");
    }

    @Test
    void deriveNextSnapshot_alreadySnapshot_stillWorks() {
        assertThat(ReleaseSupport.deriveNextSnapshot("1.0.0-SNAPSHOT"))
                .isEqualTo("1.0.1-SNAPSHOT");
    }

    // ── validateRemotePath ───────────────────────────────────────────

    @Test
    void validateRemotePath_validPath_noException() throws MojoExecutionException {
        // Should not throw — path has base + project + type
        ReleaseSupport.validateRemotePath("/srv/ike-site/ike-pipeline/snapshot");
    }

    @Test
    void validateRemotePath_deepPath_noException() throws MojoExecutionException {
        ReleaseSupport.validateRemotePath("/srv/ike-site/ike-pipeline/snapshot/main");
    }

    @Test
    void validateRemotePath_wrongBase_throws() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/tmp/evil/path"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("does not start with");
    }

    @Test
    void validateRemotePath_tooShallow_throws() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("too shallow");
    }

    @Test
    void validateRemotePath_justBase_throws() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/"))
                .isInstanceOf(MojoExecutionException.class);
    }

    // ── siteDiskPath ─────────────────────────────────────────────────

    @Test
    void siteDiskPath_release() {
        assertThat(ReleaseSupport.siteDiskPath("ike-pipeline", "release", null))
                .isEqualTo("/srv/ike-site/ike-pipeline/release");
    }

    @Test
    void siteDiskPath_snapshotWithBranch() {
        assertThat(ReleaseSupport.siteDiskPath("ike-pipeline", "snapshot", "main"))
                .isEqualTo("/srv/ike-site/ike-pipeline/snapshot/main");
    }

    @Test
    void siteDiskPath_checkpoint() {
        assertThat(ReleaseSupport.siteDiskPath("ike-docs", "checkpoint", "v1.0"))
                .isEqualTo("/srv/ike-site/ike-docs/checkpoint/v1.0");
    }

    @Test
    void siteDiskPath_blankSubPath_noTrailingSlash() {
        assertThat(ReleaseSupport.siteDiskPath("proj", "release", ""))
                .isEqualTo("/srv/ike-site/proj/release");
    }

    // ── branchToSitePath ─────────────────────────────────────────────

    @Test
    void branchToSitePath_main_unchanged() {
        assertThat(ReleaseSupport.branchToSitePath("main"))
                .isEqualTo("main");
    }

    @Test
    void branchToSitePath_featureBranch_preservesSlash() {
        assertThat(ReleaseSupport.branchToSitePath("feature/my-work"))
                .isEqualTo("feature/my-work");
    }

    @Test
    void branchToSitePath_unsafeChars_replaced() {
        assertThat(ReleaseSupport.branchToSitePath("feature/weird@chars!"))
                .isEqualTo("feature/weird-chars-");
    }

    // ── siteStagingPath ──────────────────────────────────────────────

    @Test
    void siteStagingPath_appendsSuffix() {
        assertThat(ReleaseSupport.siteStagingPath("/srv/ike-site/proj/release"))
                .isEqualTo("/srv/ike-site/proj/release.staging");
    }

    // ── siteStagingUrl ──────────────────────────────────────────────

    @Test
    void siteStagingUrl_appendsSuffix() {
        assertThat(ReleaseSupport.siteStagingUrl("scpexe://proxy/srv/ike-site/proj/release"))
                .isEqualTo("scpexe://proxy/srv/ike-site/proj/release.staging");
    }

    // ── readPomVersion (file-based) ─────────────────────────────────

    @Test
    void readPomVersion_simpleProject(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>3.1.0-SNAPSHOT</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomVersion(pom))
                .isEqualTo("3.1.0-SNAPSHOT");
    }

    @Test
    void readPomVersion_skipsParentVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomVersion(pom))
                .isEqualTo("1.0.0");
    }

    @Test
    void readPomVersion_noVersion_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>orphan</artifactId>
                </project>
                """);

        assertThatThrownBy(() -> ReleaseSupport.readPomVersion(pom))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Could not extract <version>");
    }

    @Test
    void readPomVersion_integerVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>ike-pipeline</artifactId>
                    <version>20</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomVersion(pom))
                .isEqualTo("20");
    }

    // ── readPomArtifactId (file-based) ──────────────────────────────

    @Test
    void readPomArtifactId_simpleProject(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>ike-pipeline</artifactId>
                    <version>20-SNAPSHOT</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomArtifactId(pom))
                .isEqualTo("ike-pipeline");
    }

    @Test
    void readPomArtifactId_skipsParentArtifactId(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                </project>
                """);

        assertThat(ReleaseSupport.readPomArtifactId(pom))
                .isEqualTo("child-module");
    }

    @Test
    void readPomArtifactId_noArtifactId_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <version>1.0</version>
                </project>
                """);

        assertThatThrownBy(() -> ReleaseSupport.readPomArtifactId(pom))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Could not extract <artifactId>");
    }

    // ── setPomVersion (file-based) ──────────────────────────────────

    @Test
    void setPomVersion_replacesProjectVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "1.0.0-SNAPSHOT", "1.0.0");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("<version>1.0.0</version>")
                .doesNotContain("SNAPSHOT");
    }

    @Test
    void setPomVersion_skipsParentVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "1.0.0-SNAPSHOT", "1.0.0");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("<version>20-SNAPSHOT</version>")  // parent unchanged
                .contains("<version>1.0.0</version>");       // project updated
    }

    @Test
    void setPomVersion_versionNotFound_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>2.0.0</version>
                </project>
                """);

        assertThatThrownBy(() ->
                ReleaseSupport.setPomVersion(pom, "999.0.0", "999.0.1"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("does not contain");
    }

    // ── validateRemotePath (additional edge cases) ──────────────────

    @Test
    void validateRemotePath_projectOnly_noSlash_valid() throws MojoExecutionException {
        // "ike-pipeline" has no slash but is not blank — depth=0 which is < 1
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/ike-pipeline"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("too shallow");
    }

    // ── findPomFiles (file-based) ──────────────────────────────────

    @Test
    void findPomFiles_findsRootPom(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(1);
        assertThat(poms.get(0).getName()).isEqualTo("pom.xml");
    }

    @Test
    void findPomFiles_findsNestedPoms(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        Files.createDirectories(tmpDir.resolve("sub-a"));
        writePom(tmpDir.resolve("sub-a"), "<project/>");
        Files.createDirectories(tmpDir.resolve("sub-b"));
        writePom(tmpDir.resolve("sub-b"), "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(3);
    }

    @Test
    void findPomFiles_excludesTargetDirectory(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        Files.createDirectories(tmpDir.resolve("target/classes"));
        writePom(tmpDir.resolve("target"), "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(1);
    }

    @Test
    void findPomFiles_excludesMvnDirectory(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        Files.createDirectories(tmpDir.resolve(".mvn/wrapper"));
        writePom(tmpDir.resolve(".mvn"), "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(1);
    }

    // ── replaceProjectVersionRefs + restoreBackups (file-based) ────

    @Test
    void replaceProjectVersionRefs_replacesExpression(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, """
                <project>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "2.0.0", log);

        assertThat(modified).hasSize(1);
        String content = Files.readString(modified.get(0).toPath(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("<version>2.0.0</version>")
                .doesNotContain("${project.version}");
    }

    @Test
    void replaceProjectVersionRefs_createsBackup(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project><version>${project.version}</version></project>");

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        ReleaseSupport.replaceProjectVersionRefs(tmpDir.toFile(), "3.0", log);

        Path backup = tmpDir.resolve("pom.xml.ike-backup");
        assertThat(backup).exists();
        String backupContent = Files.readString(backup, StandardCharsets.UTF_8);
        assertThat(backupContent).contains("${project.version}");
    }

    @Test
    void replaceProjectVersionRefs_skipsFilesWithoutExpression(@TempDir Path tmpDir)
            throws Exception {
        writePom(tmpDir, "<project><version>1.0.0</version></project>");

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "2.0.0", log);

        assertThat(modified).isEmpty();
    }

    @Test
    void restoreBackups_restoresFromBackup(@TempDir Path tmpDir) throws Exception {
        String original = "<project><version>${project.version}</version></project>";
        writePom(tmpDir, original);

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        ReleaseSupport.replaceProjectVersionRefs(tmpDir.toFile(), "3.0", log);

        // Now restore
        var restored = ReleaseSupport.restoreBackups(tmpDir.toFile(), log);

        assertThat(restored).hasSize(1);
        String content = Files.readString(restored.get(0).toPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("${project.version}");

        // Backup should be deleted
        Path backup = tmpDir.resolve("pom.xml.ike-backup");
        assertThat(backup).doesNotExist();
    }

    @Test
    void restoreBackups_noBackups_emptyResult(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        var restored = ReleaseSupport.restoreBackups(tmpDir.toFile(), log);

        assertThat(restored).isEmpty();
    }

    // ── routeSubprocessLine ─────────────────────────────────────────

    @Test
    void routeSubprocessLine_errorPrefix_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[ERROR] Something went wrong");
        assertThat(log.errors).containsExactly("Something went wrong");
    }

    @Test
    void routeSubprocessLine_warningPrefix_routesToWarn() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[WARNING] Deprecated API");
        assertThat(log.warnings).containsExactly("Deprecated API");
    }

    @Test
    void routeSubprocessLine_infoPrefix_routesToInfo() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[INFO] Building project");
        assertThat(log.infos).containsExactly("Building project");
    }

    @Test
    void routeSubprocessLine_debugPrefix_routesToDebug() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[DEBUG] Classpath entry");
        assertThat(log.debugs).containsExactly("Classpath entry");
    }

    @Test
    void routeSubprocessLine_jvmWarning_routesToWarn() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "WARNING: sun.misc.Unsafe deprecated");
        assertThat(log.warnings).containsExactly("sun.misc.Unsafe deprecated");
    }

    @Test
    void routeSubprocessLine_jvmError_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "ERROR: fatal JVM error");
        assertThat(log.errors).containsExactly("fatal JVM error");
    }

    @Test
    void routeSubprocessLine_plainText_routesToInfo() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "Just a plain line");
        assertThat(log.infos).containsExactly("Just a plain line");
    }

    @Test
    void routeSubprocessLine_withPrefix_prependsLabel() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[INFO] Building", "[nexus] ");
        assertThat(log.infos).containsExactly("[nexus] Building");
    }

    // ── exec / execCapture (with real processes) ────────────────────

    @Test
    void exec_successfulCommand_noException(@TempDir Path tmpDir) {
        var log = new SystemStreamLog();
        assertThatCode(() ->
                ReleaseSupport.exec(tmpDir.toFile(), log, "echo", "hello"))
                .doesNotThrowAnyException();
    }

    @Test
    void exec_failingCommand_throwsWithExitCode(@TempDir Path tmpDir) {
        var log = new SystemStreamLog();
        assertThatThrownBy(() ->
                ReleaseSupport.exec(tmpDir.toFile(), log, "false"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("exit 1");
    }

    @Test
    void execCapture_capturesOutput(@TempDir Path tmpDir) throws Exception {
        String result = ReleaseSupport.execCapture(tmpDir.toFile(),
                "echo", "hello world");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void execCapture_failingCommand_throws(@TempDir Path tmpDir) {
        assertThatThrownBy(() ->
                ReleaseSupport.execCapture(tmpDir.toFile(), "false"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("exit 1");
    }

    // ── execParallel ────────────────────────────────────────────────

    @Test
    void execParallel_twoSuccessfulTasks(@TempDir Path tmpDir) {
        var log = new SystemStreamLog();
        assertThatCode(() -> ReleaseSupport.execParallel(
                tmpDir.toFile(), log,
                new ReleaseSupport.LabeledTask("a", new String[]{"echo", "alpha"}),
                new ReleaseSupport.LabeledTask("b", new String[]{"echo", "beta"})
        )).doesNotThrowAnyException();
    }

    @Test
    void execParallel_oneFailingTask_throwsWithLabel(@TempDir Path tmpDir) {
        var log = new SystemStreamLog();
        assertThatThrownBy(() -> ReleaseSupport.execParallel(
                tmpDir.toFile(), log,
                new ReleaseSupport.LabeledTask("good", new String[]{"echo", "ok"}),
                new ReleaseSupport.LabeledTask("bad", new String[]{"false"})
        ))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("bad");
    }

    @Test
    void execParallel_bothFailing_reportsAll(@TempDir Path tmpDir) {
        var log = new SystemStreamLog();
        assertThatThrownBy(() -> ReleaseSupport.execParallel(
                tmpDir.toFile(), log,
                new ReleaseSupport.LabeledTask("task1", new String[]{"false"}),
                new ReleaseSupport.LabeledTask("task2", new String[]{"false"})
        ))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("task1")
                .hasMessageContaining("task2");
    }

    // ── gitAddFiles ─────────────────────────────────────────────────

    @Test
    void gitAddFiles_emptyList_noOp(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        var log = new SystemStreamLog();

        // Should not throw — empty list is a no-op
        assertThatCode(() ->
                ReleaseSupport.gitAddFiles(tmpDir.toFile(), log, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void gitAddFiles_stagesFiles(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        var log = new SystemStreamLog();

        // Create a new file
        Path newFile = tmpDir.resolve("new.txt");
        Files.writeString(newFile, "new content", StandardCharsets.UTF_8);

        ReleaseSupport.gitAddFiles(tmpDir.toFile(), log,
                List.of(newFile.toFile()));

        // Verify the file is staged
        String status = execCapture(tmpDir, "git", "status", "--porcelain");
        assertThat(status).contains("A  new.txt");
    }

    // ── hasRemote ───────────────────────────────────────────────────

    @Test
    void hasRemote_noRemotes_returnsFalse(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        assertThat(ReleaseSupport.hasRemote(tmpDir.toFile(), "origin"))
                .isFalse();
    }

    @Test
    void hasRemote_originExists_returnsTrue(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "remote", "add", "origin", "https://example.com/repo.git");

        assertThat(ReleaseSupport.hasRemote(tmpDir.toFile(), "origin"))
                .isTrue();
    }

    @Test
    void hasRemote_differentRemote_returnsFalse(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "remote", "add", "upstream", "https://example.com/repo.git");

        assertThat(ReleaseSupport.hasRemote(tmpDir.toFile(), "origin"))
                .isFalse();
    }

    // ── requireCleanWorktree ────────────────────────────────────────

    @Test
    void requireCleanWorktree_clean_noException(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);

        assertThatCode(() ->
                ReleaseSupport.requireCleanWorktree(tmpDir.toFile()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireCleanWorktree_unstagedChanges_throws(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        // Modify the committed file
        Files.writeString(tmpDir.resolve("init.txt"), "modified",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                ReleaseSupport.requireCleanWorktree(tmpDir.toFile()))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("unstaged");
    }

    @Test
    void requireCleanWorktree_stagedChanges_throws(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        Files.writeString(tmpDir.resolve("staged.txt"), "new",
                StandardCharsets.UTF_8);
        exec(tmpDir, "git", "add", "staged.txt");

        assertThatThrownBy(() ->
                ReleaseSupport.requireCleanWorktree(tmpDir.toFile()))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("staged");
    }

    // ── currentBranch ───────────────────────────────────────────────

    @Test
    void currentBranch_returnsMainByDefault(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        assertThat(ReleaseSupport.currentBranch(tmpDir.toFile()))
                .isEqualTo("main");
    }

    @Test
    void currentBranch_afterCheckout(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "checkout", "-b", "feature/test");

        assertThat(ReleaseSupport.currentBranch(tmpDir.toFile()))
                .isEqualTo("feature/test");
    }

    // ── gitRoot ─────────────────────────────────────────────────────

    @Test
    void gitRoot_returnsRepoRoot(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        Path subDir = tmpDir.resolve("a/b/c");
        Files.createDirectories(subDir);

        File root = ReleaseSupport.gitRoot(subDir.toFile());
        assertThat(root.getCanonicalPath())
                .isEqualTo(tmpDir.toFile().getCanonicalPath());
    }

    // ── tagExists ───────────────────────────────────────────────────

    @Test
    void tagExists_existingTag_returnsTrue(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "tag", "v1.0.0");

        assertThat(ReleaseSupport.tagExists(tmpDir.toFile(), "v1.0.0"))
                .isTrue();
    }

    @Test
    void tagExists_missingTag_returnsFalse(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);

        assertThat(ReleaseSupport.tagExists(tmpDir.toFile(), "v999"))
                .isFalse();
    }

    // ── deriveCheckpointVersion ─────────────────────────────────────

    @Test
    void deriveCheckpointVersion_usesShortSha(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        String shortSha = execCapture(tmpDir, "git", "rev-parse", "--short", "HEAD");

        String version = ReleaseSupport.deriveCheckpointVersion(
                "2.0.0-SNAPSHOT", tmpDir.toFile());

        assertThat(version)
                .startsWith("2.0.0-checkpoint.")
                .endsWith("." + shortSha);
    }

    @Test
    void deriveCheckpointVersion_stripsSnapshot(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        String shortSha = execCapture(tmpDir, "git", "rev-parse", "--short", "HEAD");

        String version = ReleaseSupport.deriveCheckpointVersion(
                "1.127.2-SNAPSHOT", tmpDir.toFile());

        assertThat(version).startsWith("1.127.2-checkpoint.");
        assertThat(version).endsWith("." + shortSha);
        assertThat(version).doesNotContain("SNAPSHOT");
    }

    @Test
    void deriveCheckpointVersion_deterministicAcrossCalls(@TempDir Path tmpDir)
            throws Exception {
        initGitRepo(tmpDir);

        // Same commit → same version every time (no sequence counter race)
        String v1 = ReleaseSupport.deriveCheckpointVersion("3.0.0-SNAPSHOT", tmpDir.toFile());
        String v2 = ReleaseSupport.deriveCheckpointVersion("3.0.0-SNAPSHOT", tmpDir.toFile());

        assertThat(v1).isEqualTo(v2);
    }

    // ── resolveMavenWrapper ─────────────────────────────────────────

    @Test
    void resolveMavenWrapper_withWrapper(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        Path mvnw = tmpDir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho mvnw", StandardCharsets.UTF_8);
        mvnw.toFile().setExecutable(true);

        File result = ReleaseSupport.resolveMavenWrapper(
                tmpDir.toFile(), new SystemStreamLog());
        assertThat(result.getAbsolutePath()).isEqualTo(mvnw.toAbsolutePath().toString());
    }

    // ── readPomVersion: version only in parent ─────────────────────

    @Test
    void readPomVersion_versionOnlyInParent_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child-no-version</artifactId>
                </project>
                """);

        // After stripping <parent>, no <version> remains → should throw
        assertThatThrownBy(() -> ReleaseSupport.readPomVersion(pom))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Could not extract <version>");
    }

    @Test
    void readPomVersion_minimalValidPom_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);

        assertThatThrownBy(() -> ReleaseSupport.readPomVersion(pom))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Could not extract <version>");
    }

    // ── setPomVersion: edge cases ────────────────────────────────────

    @Test
    void setPomVersion_noParentBlock_replacesFirstVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>simple</artifactId>
                    <version>5.0.0</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "5.0.0", "5.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("<version>5.0.1</version>");
        assertThat(content).doesNotContain("<version>5.0.0</version>");
    }

    @Test
    void setPomVersion_versionInComment_notReplaced(@TempDir Path tmpDir) throws Exception {
        // The old version appears in a comment AND as the real version.
        // setPomVersion does text replacement, so the comment version
        // will NOT be replaced (it looks for the exact <version>X</version>
        // tag after the parent block). Document current behavior.
        File pom = writePom(tmpDir, """
                <project>
                    <!-- Current version is 3.0.0 -->
                    <groupId>network.ike</groupId>
                    <artifactId>app</artifactId>
                    <version>3.0.0</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "3.0.0", "3.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        // The comment text "3.0.0" inside <!-- ... --> should remain
        // because setPomVersion only replaces <version>3.0.0</version>
        assertThat(content).contains("<!-- Current version is 3.0.0 -->");
        assertThat(content).contains("<version>3.0.1</version>");
    }

    @Test
    void setPomVersion_cdataSection_notAffected(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                    <description><![CDATA[Version 1.0.0 notes]]></description>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "1.0.0", "1.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        // CDATA contains "1.0.0" but NOT in a <version> tag, so unchanged
        assertThat(content).contains("Version 1.0.0 notes");
        assertThat(content).contains("<version>1.0.1</version>");
    }

    @Test
    void setPomVersion_parentHasSameVersion_onlyProjectVersionChanged(@TempDir Path tmpDir)
            throws Exception {
        // Both parent and project have the same version string.
        // setPomVersion should only change the project version (after parent block).
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>2.0.0</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "2.0.0", "2.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        // Parent version unchanged, project version changed
        assertThat(content)
                .containsOnlyOnce("<version>2.0.1</version>")
                .contains("<version>2.0.0</version>");  // parent still has old version
    }

    // ── findPomFiles: additional edge cases ──────────────────────────

    @Test
    void findPomFiles_noPomFiles_emptyResult(@TempDir Path tmpDir) throws Exception {
        // Directory with no pom.xml files at all
        Files.writeString(tmpDir.resolve("README.txt"), "hello", StandardCharsets.UTF_8);

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());
        assertThat(poms).isEmpty();
    }

    @Test
    void findPomFiles_deeplyNestedTarget_excluded(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        // Nested target directories should all be excluded
        Path deep = tmpDir.resolve("sub/target/nested/deep");
        Files.createDirectories(deep);
        writePom(deep, "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());
        assertThat(poms).hasSize(1);  // only root pom
    }

    @Test
    void findPomFiles_singlePom_noSubmodules(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project><version>1.0</version></project>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());
        assertThat(poms).hasSize(1);
        assertThat(poms.get(0).getParentFile().toPath()).isEqualTo(tmpDir);
    }

    // ── replaceProjectVersionRefs: edge cases ────────────────────────

    @Test
    void replaceProjectVersionRefs_multipleRefsInOnePom(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, """
                <project>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <version>${project.version}</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "2.0.0", log);

        assertThat(modified).hasSize(1);
        String content = Files.readString(modified.get(0).toPath(), StandardCharsets.UTF_8);
        // Both occurrences should be replaced
        assertThat(content).doesNotContain("${project.version}");
        // Count: two version elements should now have literal "2.0.0"
        long count = content.lines()
                .filter(line -> line.contains("<version>2.0.0</version>"))
                .count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void replaceProjectVersionRefs_nestedSubmodules(@TempDir Path tmpDir) throws Exception {
        // Root has no ${project.version}, but two submodules do
        writePom(tmpDir, "<project><version>1.0</version></project>");

        Path subA = tmpDir.resolve("sub-a");
        Files.createDirectories(subA);
        writePom(subA, """
                <project>
                    <parent><version>1.0</version></parent>
                    <version>${project.version}</version>
                </project>
                """);

        Path subB = tmpDir.resolve("sub-b");
        Files.createDirectories(subB);
        writePom(subB, """
                <project>
                    <dependencies>
                        <dependency><version>${project.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "3.0.0", log);

        assertThat(modified).hasSize(2);
    }

    @Test
    void replaceProjectVersionRefs_expressionInXmlComment_stillReplaced(@TempDir Path tmpDir)
            throws Exception {
        // ${project.version} in an XML comment IS replaced (text-level replacement).
        // This documents current behavior.
        writePom(tmpDir, """
                <project>
                    <!-- ref: ${project.version} -->
                    <dependencies>
                        <dependency><version>${project.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        var log = new org.apache.maven.plugin.logging.SystemStreamLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "4.0.0", log);

        assertThat(modified).hasSize(1);
        String content = Files.readString(modified.get(0).toPath(), StandardCharsets.UTF_8);
        // Both comment and element occurrences replaced
        assertThat(content).doesNotContain("${project.version}");
    }

    // ── helper ──────────────────────────────────────────────────────

    private static File writePom(Path dir, String content) throws IOException {
        Path pomPath = dir.resolve("pom.xml");
        Files.writeString(pomPath, content, StandardCharsets.UTF_8);
        return pomPath.toFile();
    }

    private void initGitRepo(Path dir) throws Exception {
        Files.writeString(dir.resolve("init.txt"), "init", StandardCharsets.UTF_8);
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

    /**
     * Simple log implementation that captures messages by level.
     */
    private static class CapturingLog implements Log {
        final List<String> debugs = new ArrayList<>();
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public boolean isDebugEnabled() { return true; }
        @Override public void debug(CharSequence content) { debugs.add(content.toString()); }
        @Override public void debug(CharSequence content, Throwable error) { debugs.add(content.toString()); }
        @Override public void debug(Throwable error) { debugs.add(error.getMessage()); }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence content) { infos.add(content.toString()); }
        @Override public void info(CharSequence content, Throwable error) { infos.add(content.toString()); }
        @Override public void info(Throwable error) { infos.add(error.getMessage()); }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(CharSequence content) { warnings.add(content.toString()); }
        @Override public void warn(CharSequence content, Throwable error) { warnings.add(content.toString()); }
        @Override public void warn(Throwable error) { warnings.add(error.getMessage()); }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(CharSequence content) { errors.add(content.toString()); }
        @Override public void error(CharSequence content, Throwable error) { errors.add(content.toString()); }
        @Override public void error(Throwable error) { errors.add(error.getMessage()); }
    }
}
