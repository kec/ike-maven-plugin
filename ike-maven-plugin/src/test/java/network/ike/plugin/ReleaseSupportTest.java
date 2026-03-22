package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ── helper ──────────────────────────────────────────────────────

    private static File writePom(Path dir, String content) throws IOException {
        Path pomPath = dir.resolve("pom.xml");
        Files.writeString(pomPath, content, StandardCharsets.UTF_8);
        return pomPath.toFile();
    }
}
