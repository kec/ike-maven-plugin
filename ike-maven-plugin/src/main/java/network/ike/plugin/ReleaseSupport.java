package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared utilities for release mojos.
 *
 * <p>All subprocess invocations use {@link ProcessBuilder} — no
 * library dependencies beyond the JDK and maven-plugin-api.
 */
class ReleaseSupport {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");

    private ReleaseSupport() {}

    /**
     * Run a command, inherit IO so output streams to the Maven console.
     * Throws on non-zero exit code.
     */
    static void exec(File workDir, Log log, String... command)
            throws MojoExecutionException {
        log.info("» " + String.join(" ", command));
        try {
            Process proc = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start();
            // Route subprocess output through Maven's logger instead of
            // inheritIO(), which corrupts surefire's fork protocol.
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[stdout] " + line);
                }
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new MojoExecutionException(
                        "Command failed (exit " + exit + "): " +
                                String.join(" ", command));
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    record LabeledTask(String label, String[] command) {}

    /**
     * Run multiple commands concurrently, prefixing each line of output
     * with the task's label (e.g., {@code [nexus] ...}).
     *
     * <p>Spawns virtual threads to read stdout/stderr from each process.
     * All processes run to completion even if one fails — the exception
     * reports which task(s) failed.
     */
    static void execParallel(File workDir, Log log, LabeledTask... tasks)
            throws MojoExecutionException {
        for (LabeledTask task : tasks) {
            log.info("» [" + task.label() + "] " + String.join(" ", task.command()));
        }

        List<String> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (LabeledTask task : tasks) {
            Thread thread = Thread.ofVirtual()
                    .name("exec-" + task.label())
                    .start(() -> {
                        try {
                            Process process = new ProcessBuilder(task.command())
                                    .directory(workDir)
                                    .redirectErrorStream(true)
                                    .start();

                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(process.getInputStream(),
                                            StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    synchronized (log) {
                                        log.info("[" + task.label() + "] " + line);
                                    }
                                }
                            }

                            int exit = process.waitFor();
                            if (exit != 0) {
                                failures.add(task.label() + " (exit " + exit + ")");
                            }
                        } catch (IOException | InterruptedException e) {
                            failures.add(task.label() + " (" + e.getMessage() + ")");
                        }
                    });
            threads.add(thread);
        }

        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Parallel execution interrupted", e);
        }

        if (!failures.isEmpty()) {
            throw new MojoExecutionException(
                    "Parallel tasks failed: " + String.join(", ", failures));
        }
    }

    /**
     * Run a command and capture stdout as a trimmed String.
     * Throws on non-zero exit code.
     */
    static String execCapture(File workDir, String... command)
            throws MojoExecutionException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(false)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new MojoExecutionException(
                        "Command failed (exit " + exit + "): " +
                                String.join(" ", command));
            }
            return output;
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    /**
     * Read the project's own {@code <version>} from a POM file,
     * skipping any {@code <version>} inside the {@code <parent>} block.
     */
    static String readPomVersion(File pomFile) throws MojoExecutionException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);

            // Strip the <parent>...</parent> block so we don't match
            // the parent version instead of the project version.
            String stripped = content.replaceFirst(
                    "(?s)<parent>.*?</parent>", "");
            Matcher matcher = VERSION_PATTERN.matcher(stripped);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new MojoExecutionException(
                    "Could not extract <version> from " + pomFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + pomFile, e);
        }
    }

    /**
     * Replace the project's own {@code <version>old</version>} with
     * {@code <version>new</version>}, skipping any version inside
     * the {@code <parent>} block.
     */
    static void setPomVersion(File pomFile, String oldVersion, String newVersion)
            throws MojoExecutionException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            String oldTag = "<version>" + oldVersion + "</version>";
            String newTag = "<version>" + newVersion + "</version>";

            // Find the end of the <parent> block (if any) so we skip it
            int searchStart = 0;
            Matcher parentEnd = Pattern.compile("</parent>").matcher(content);
            if (parentEnd.find()) {
                searchStart = parentEnd.end();
            }

            int idx = content.indexOf(oldTag, searchStart);
            if (idx < 0) {
                throw new MojoExecutionException(
                        "POM does not contain " + oldTag +
                                " (outside <parent> block)");
            }
            String updated = content.substring(0, idx) + newTag +
                    content.substring(idx + oldTag.length());
            Files.writeString(pomFile.toPath(), updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to update " + pomFile, e);
        }
    }

    /**
     * Resolve the Maven executable. Prefers the Maven wrapper
     * ({@code mvnw}) at the git root; falls back to {@code mvn}
     * from the system PATH (resolved via {@code which}).
     */
    static File resolveMavenWrapper(File gitRoot, Log log) throws MojoExecutionException {
        String name = System.getProperty("os.name", "")
                .toLowerCase().contains("win") ? "mvnw.cmd" : "mvnw";
        File wrapper = new File(gitRoot, name);
        if (wrapper.exists()) {
            return wrapper;
        }
        // Fall back to system mvn — resolve via PATH
        String systemName = name.replace("mvnw", "mvn");
        try {
            String path = execCapture(gitRoot, "which", systemName);
            log.info("No Maven wrapper found; using system '" + path + "'");
            return new File(path);
        } catch (MojoExecutionException _) {
            throw new MojoExecutionException(
                    "Neither Maven wrapper (" + wrapper.getAbsolutePath() +
                            ") nor system '" + systemName + "' found on PATH.");
        }
    }

    /**
     * Get the git repository root directory.
     */
    static File gitRoot(File startDir) throws MojoExecutionException {
        String root = execCapture(startDir,
                "git", "rev-parse", "--show-toplevel");
        return new File(root);
    }

    /**
     * Assert that the git working tree is clean (no staged or unstaged changes).
     */
    static void requireCleanWorktree(File workDir) throws MojoExecutionException {
        try {
            execCapture(workDir, "git", "diff", "--quiet");
        } catch (MojoExecutionException _) {
            throw new MojoExecutionException(
                    "Working tree has unstaged changes. Commit or stash before proceeding.");
        }
        try {
            execCapture(workDir, "git", "diff", "--cached", "--quiet");
        } catch (MojoExecutionException _) {
            throw new MojoExecutionException(
                    "Working tree has staged changes. Commit or stash before proceeding.");
        }
    }

    /**
     * Get the current git branch name.
     */
    static String currentBranch(File workDir) throws MojoExecutionException {
        return execCapture(workDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * Check whether a named git remote exists.
     */
    static boolean hasRemote(File workDir, String remoteName) {
        try {
            String remotes = execCapture(workDir, "git", "remote");
            return remotes.lines().anyMatch(line -> line.trim().equals(remoteName));
        } catch (MojoExecutionException _) {
            return false;
        }
    }

    /**
     * Derive the release version from a SNAPSHOT version.
     * {@code "2-SNAPSHOT"} becomes {@code "2"};
     * {@code "1.1.0-SNAPSHOT"} becomes {@code "1.1.0"}.
     */
    static String deriveReleaseVersion(String snapshotVersion) {
        return snapshotVersion.replace("-SNAPSHOT", "");
    }

    /**
     * Derive the next SNAPSHOT version by incrementing the last numeric
     * segment. {@code "2"} becomes {@code "3-SNAPSHOT"};
     * {@code "1.1.0"} becomes {@code "1.1.1-SNAPSHOT"}.
     */
    static String deriveNextSnapshot(String releaseVersion) {
        String base = releaseVersion.replace("-SNAPSHOT", "");
        int lastDot = base.lastIndexOf('.');
        if (lastDot >= 0) {
            String prefix = base.substring(0, lastDot + 1);
            String last = base.substring(lastDot + 1);
            return prefix + (Integer.parseInt(last) + 1) + "-SNAPSHOT";
        }
        // Simple integer version (e.g., "2" -> "3-SNAPSHOT")
        return (Integer.parseInt(base) + 1) + "-SNAPSHOT";
    }

    private static final String PROJECT_VERSION_EXPR = "${project.version}";
    private static final String BACKUP_SUFFIX = ".ike-backup";

    /**
     * Find all {@code pom.xml} files under the git root, excluding
     * {@code target/} directories and the {@code .mvn/} directory.
     */
    static List<File> findPomFiles(File gitRoot) throws MojoExecutionException {
        try (Stream<Path> walk = Files.walk(gitRoot.toPath())) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        String rel = gitRoot.toPath().relativize(p).toString();
                        return !rel.contains("target" + File.separator)
                                && !rel.startsWith(".mvn" + File.separator);
                    })
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan for POM files", e);
        }
    }

    /**
     * Replace all occurrences of {@code ${project.version}} with a
     * literal version string in every POM file under the git root.
     * Before replacing, each affected file is saved as
     * {@code pom.xml.ike-backup} so it can be restored later.
     *
     * @return the list of POM files that were modified
     */
    static List<File> replaceProjectVersionRefs(File gitRoot, String version,
                                                 Log log)
            throws MojoExecutionException {
        List<File> pomFiles = findPomFiles(gitRoot);
        List<File> modified = new ArrayList<>();

        for (File pom : pomFiles) {
            try {
                String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
                if (!content.contains(PROJECT_VERSION_EXPR)) {
                    continue;
                }
                // Save backup before modifying
                Path backup = pom.toPath().resolveSibling(pom.getName() + BACKUP_SUFFIX);
                Files.copy(pom.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);

                // Replace all occurrences
                String updated = content.replace(PROJECT_VERSION_EXPR, version);
                Files.writeString(pom.toPath(), updated, StandardCharsets.UTF_8);

                String rel = gitRoot.toPath().relativize(pom.toPath()).toString();
                log.info("  Resolved ${project.version} -> " + version +
                        " in " + rel);
                modified.add(pom);
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to process " + pom, e);
            }
        }
        return modified;
    }

    /**
     * Restore all POM files from their {@code .ike-backup} copies and
     * delete the backup files. This reverses
     * {@link #replaceProjectVersionRefs}.
     *
     * @return the list of POM files that were restored
     */
    static List<File> restoreBackups(File gitRoot, Log log)
            throws MojoExecutionException {
        List<File> pomFiles = findPomFiles(gitRoot);
        List<File> restored = new ArrayList<>();

        for (File pom : pomFiles) {
            Path backup = pom.toPath().resolveSibling(pom.getName() + BACKUP_SUFFIX);
            if (!Files.exists(backup)) {
                continue;
            }
            try {
                Files.copy(backup, pom.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(backup);

                String rel = gitRoot.toPath().relativize(pom.toPath()).toString();
                log.info("  Restored ${project.version} in " + rel);
                restored.add(pom);
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to restore backup for " + pom, e);
            }
        }
        return restored;
    }

    /**
     * Stage a list of files with {@code git add}.
     */
    static void gitAddFiles(File gitRoot, Log log, List<File> files)
            throws MojoExecutionException {
        if (files.isEmpty()) return;
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("add");
        for (File f : files) {
            command.add(gitRoot.toPath().relativize(f.toPath()).toString());
        }
        exec(gitRoot, log, command.toArray(new String[0]));
    }

    private static final DateTimeFormatter CHECKPOINT_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Derive a checkpoint version from the current POM version.
     *
     * <p>Format: {@code {base}-checkpoint.{yyyyMMdd}.{seq}} where
     * {@code base} is the POM version minus {@code -SNAPSHOT}, and
     * {@code seq} is auto-incremented if a tag for the same date
     * already exists.
     *
     * @param pomVersion current POM version (may include -SNAPSHOT)
     * @param gitRoot    git repository root (for tag existence checks)
     */
    static String deriveCheckpointVersion(String pomVersion, File gitRoot)
            throws MojoExecutionException {
        String base = pomVersion.replace("-SNAPSHOT", "");
        String date = LocalDate.now().format(CHECKPOINT_DATE_FMT);
        int seq = 1;
        while (tagExists(gitRoot, "checkpoint/" + base + "-checkpoint." + date + "." + seq)) {
            seq++;
        }
        return base + "-checkpoint." + date + "." + seq;
    }

    /**
     * Check whether a git tag exists (locally).
     */
    static boolean tagExists(File gitRoot, String tagName) {
        try {
            execCapture(gitRoot, "git", "rev-parse", "--verify", "refs/tags/" + tagName);
            return true;
        } catch (MojoExecutionException _) {
            return false;
        }
    }

    /** Base path on the site server. */
    static final String SITE_DISK_BASE = "/srv/ike-site/";

    /** SSH host alias used by wagon-ssh-external. */
    static final String SITE_SSH_HOST = "proxy";

    /**
     * Remove a directory tree on the site server via SSH.
     *
     * <p>Used to clean up snapshot sites after release or feature-finish.
     *
     * <p>Safety: validates the path starts with {@link #SITE_DISK_BASE}
     * and contains at least two path components after the base to
     * prevent accidental deletion of the entire site root.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath absolute path on the server (e.g.,
     *                   {@code /srv/ike-site/ike-pipeline/snapshot/main})
     * @throws MojoExecutionException if the path is unsafe or SSH fails
     */
    static void cleanRemoteSiteDir(File workDir, Log log, String remotePath)
            throws MojoExecutionException {
        validateRemotePath(remotePath);
        log.info("Cleaning remote site: " + remotePath);
        exec(workDir, log,
                "ssh", SITE_SSH_HOST, "rm", "-rf", remotePath);
    }

    /**
     * Atomically swap a newly deployed site into place on the server.
     *
     * <p>The deployment flow is:
     * <ol>
     *   <li>SCP deploys to a staging path ({@code <target>.staging})</li>
     *   <li>This method renames the old directory to {@code <target>.old}</li>
     *   <li>Renames the staging directory to the final target</li>
     *   <li>Removes the old directory</li>
     * </ol>
     *
     * <p>This avoids a window where the site is missing (rm + deploy)
     * and ensures the site always serves either the old or new version.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath final target path on the server
     * @throws MojoExecutionException if SSH commands fail
     */
    static void swapRemoteSiteDir(File workDir, Log log, String remotePath)
            throws MojoExecutionException {
        validateRemotePath(remotePath);
        String staging = remotePath + ".staging";
        String old = remotePath + ".old";

        log.info("Swapping site: " + staging + " → " + remotePath);
        // Single SSH command: atomic as possible
        // - Remove any leftover .old from a previous failed swap
        // - Move current live → .old (may not exist on first deploy)
        // - Move staging → live
        // - Remove .old
        exec(workDir, log,
                "ssh", SITE_SSH_HOST,
                "rm -rf " + old
                        + " && (mv " + remotePath + " " + old + " 2>/dev/null || true)"
                        + " && mv " + staging + " " + remotePath
                        + " && rm -rf " + old);
    }

    /**
     * Return the staging path for a site deploy (final path + ".staging").
     */
    static String siteStagingPath(String diskPath) {
        return diskPath + ".staging";
    }

    /**
     * Return the scpexe URL for the staging directory.
     */
    static String siteStagingUrl(String targetUrl) {
        return targetUrl + ".staging";
    }

    /**
     * Validate that a remote path is safe for deletion operations.
     *
     * <p>Ensures the path starts with {@link #SITE_DISK_BASE} and has
     * sufficient depth to prevent accidental deletion of the site root.
     *
     * @param remotePath absolute path on the server
     * @throws MojoExecutionException if the path is unsafe
     */
    static void validateRemotePath(String remotePath)
            throws MojoExecutionException {
        if (!remotePath.startsWith(SITE_DISK_BASE)) {
            throw new MojoExecutionException(
                    "Refusing to delete — path does not start with "
                            + SITE_DISK_BASE + ": " + remotePath);
        }
        String relative = remotePath.substring(SITE_DISK_BASE.length());
        long depth = relative.chars().filter(c -> c == '/').count();
        if (relative.isBlank() || depth < 1) {
            throw new MojoExecutionException(
                    "Refusing to delete — path too shallow (need project/type): "
                            + remotePath);
        }
    }

    /**
     * Resolve the on-disk site path for a given project, type, and
     * optional subdirectory.
     *
     * @param projectId  Maven artifact ID (e.g., "ike-pipeline")
     * @param siteType   "release", "snapshot", or "checkpoint"
     * @param subPath    optional subdirectory (branch name, version);
     *                   null or blank to omit
     * @return absolute path on the server
     */
    static String siteDiskPath(String projectId, String siteType,
                               String subPath) {
        String path = SITE_DISK_BASE + projectId + "/" + siteType;
        if (subPath != null && !subPath.isBlank()) {
            path += "/" + subPath;
        }
        return path;
    }

    /**
     * Convert a git branch name to a safe site path segment.
     * Replaces {@code /} with {@code /} (keeps hierarchy for
     * {@code feature/name} structure).
     */
    static String branchToSitePath(String branch) {
        // Keep forward slashes for directory structure (feature/name → feature/name)
        // but sanitize anything dangerous
        return branch.replaceAll("[^a-zA-Z0-9/_.-]", "-");
    }

    private static final Pattern ARTIFACT_ID_PATTERN =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");

    /**
     * Read the project's own {@code <artifactId>} from a POM file,
     * skipping any {@code <artifactId>} inside the {@code <parent>} block.
     */
    static String readPomArtifactId(File pomFile) throws MojoExecutionException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            String stripped = content.replaceFirst(
                    "(?s)<parent>.*?</parent>", "");
            Matcher matcher = ARTIFACT_ID_PATTERN.matcher(stripped);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new MojoExecutionException(
                    "Could not extract <artifactId> from " + pomFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + pomFile, e);
        }
    }
}
