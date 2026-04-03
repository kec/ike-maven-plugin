package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Component;
import network.ike.workspace.Defaults;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Clone and initialize workspace components from the manifest.
 *
 * <p>Three initialization modes per component:
 * <ol>
 *   <li><b>Already cloned</b> — directory has {@code .git/}; skip.</li>
 *   <li><b>Syncthing working tree</b> — directory exists but no
 *       {@code .git/}. Initializes git in-place: {@code git init},
 *       adds the remote, fetches, and resets to match the remote branch.
 *       This preserves file content synced from another machine.</li>
 *   <li><b>Fresh clone</b> — no directory; runs {@code git clone}.</li>
 * </ol>
 *
 * <p>Components are initialized in topological (dependency) order.
 *
 * <pre>{@code
 * mvn ike:init
 * mvn ike:init -Dgroup=studio
 * mvn ike:init -Dgroup=docs
 * }</pre>
 */
@Mojo(name = "init", requiresProject = false, threadSafe = true)
public class InitWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Restrict to a named group (or single component). Default: all.
     */
    @Parameter(property = "group")
    String group;

    /** Creates this goal instance. */
    public InitWorkspaceMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Defaults defaults = graph.manifest().defaults();

        Set<String> targets;
        if (group != null && !group.isEmpty()) {
            targets = graph.expandGroup(group);
        } else {
            targets = graph.manifest().components().keySet();
        }

        // Sort in dependency order
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info("IKE Workspace — Init");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Target: " + (group != null ? group : "all")
                + " (" + sorted.size() + " components)");
        getLog().info("  Root:   " + root.getAbsolutePath());
        if (defaults.mavenVersion() != null) {
            getLog().info("  Maven:  " + defaults.mavenVersion() + " (default)");
        }
        getLog().info("");

        int cloned = 0;
        int syncthing = 0;
        int skipped = 0;
        int wrappers = 0;

        for (String name : sorted) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (gitDir.exists()) {
                // Already a git repo — still ensure wrapper and jvm.config are current
                getLog().info("  ✓ " + name + " — already initialized");
                if (ensureMavenWrapper(dir, component, defaults)) {
                    wrappers++;
                }
                ensureJvmConfig(dir);
                skipped++;
                continue;
            }

            String repo = component.repo();
            String branch = component.branch();

            if (repo == null || repo.isEmpty()) {
                getLog().warn("  ⚠ " + name + " — no repo URL, skipping");
                continue;
            }

            if (dir.exists()) {
                // Syncthing working tree — init git in-place
                getLog().info("  ↻ " + name
                        + " — initializing git in existing directory (Syncthing)");
                initSyncthingRepo(dir, repo, branch);
                installHooks(dir);
                if (ensureMavenWrapper(dir, component, defaults)) {
                    wrappers++;
                }
                ensureJvmConfig(dir);
                syncthing++;
            } else {
                // Fresh clone
                getLog().info("  ↓ " + name + " — cloning from " + repo);
                cloneRepo(root, name, repo, branch);
                File componentDir = new File(root, name);
                installHooks(componentDir);
                if (ensureMavenWrapper(componentDir, component, defaults)) {
                    wrappers++;
                }
                ensureJvmConfig(componentDir);
                cloned++;
            }
        }

        // Ensure Maven wrapper at the workspace root itself (the aggregator POM)
        if (ensureWorkspaceRootWrapper(root, defaults)) {
            wrappers++;
        }

        getLog().info("");
        getLog().info("  Done: " + cloned + " cloned, " + syncthing
                + " Syncthing-initialized, " + skipped + " already present"
                + (wrappers > 0 ? ", " + wrappers + " Maven wrappers installed/updated" : ""));
        getLog().info("");
    }

    /**
     * Initialize a git repo inside an existing directory (Syncthing case).
     * The directory has files but no .git — we init, add remote, fetch,
     * and reset to match the remote branch without overwriting working-tree files.
     */
    private void initSyncthingRepo(File dir, String repo, String branch)
            throws MojoExecutionException {
        ReleaseSupport.exec(dir, getLog(), "git", "init");
        ReleaseSupport.exec(dir, getLog(), "git", "remote", "add", "origin", repo);
        ReleaseSupport.exec(dir, getLog(), "git", "fetch", "origin", branch);
        // Mixed reset: updates HEAD and index to match remote, keeps working tree
        ReleaseSupport.exec(dir, getLog(),
                "git", "reset", "origin/" + branch);
    }

    /**
     * Standard git clone into a new directory.
     */
    private void cloneRepo(File root, String name, String repo, String branch)
            throws MojoExecutionException {
        ReleaseSupport.exec(root, getLog(),
                "git", "clone", "-b", branch, repo, name);
    }

    /**
     * Resolve the effective Maven version for a component: component override,
     * then workspace default, then null (no wrapper).
     */
    private String resolveMavenVersion(Component component, Defaults defaults) {
        if (component.mavenVersion() != null) {
            return component.mavenVersion();
        }
        return defaults.mavenVersion();
    }

    /**
     * Ensure the Maven wrapper is installed at the workspace root directory.
     * The workspace aggregator POM needs the wrapper so that IntelliJ (and
     * command-line builds) resolve to the correct Maven version rather than
     * falling back to the system default.
     *
     * <p>Uses the workspace default maven-version from {@code workspace.yaml}.
     *
     * @param root     the workspace root directory
     * @param defaults workspace defaults
     * @return true if wrapper was installed or updated
     */
    private boolean ensureWorkspaceRootWrapper(File root, Defaults defaults) {
        String mavenVersion = defaults.mavenVersion();
        if (mavenVersion == null) {
            return false;
        }

        File pomFile = new File(root, "pom.xml");
        if (!pomFile.exists()) {
            return false;
        }

        try {
            Path wrapperDir = root.toPath().resolve(".mvn").resolve("wrapper");
            Path propsFile = wrapperDir.resolve("maven-wrapper.properties");

            if (propsFile.toFile().exists()) {
                Properties existing = new Properties();
                try (var reader = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
                    existing.load(reader);
                }
                String currentVersion = existing.getProperty("maven.version");
                if (mavenVersion.equals(currentVersion)) {
                    getLog().debug("    Workspace root Maven wrapper already at " + mavenVersion);
                    return false;
                }
                getLog().info("  ↻ workspace root — updating Maven wrapper: "
                        + currentVersion + " → " + mavenVersion);
            } else {
                getLog().info("  + workspace root — installing Maven wrapper for Maven "
                        + mavenVersion);
            }

            Files.createDirectories(wrapperDir);

            String props = "# Maven Wrapper properties — managed by ike:init from workspace.yaml\n"
                    + "maven.version=" + mavenVersion + "\n"
                    + "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
                    + "apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion
                    + "-bin.zip\n";
            Files.writeString(propsFile, props, StandardCharsets.UTF_8);

            Path mvnw = root.toPath().resolve("mvnw");
            if (!mvnw.toFile().exists()) {
                writeMvnwScript(mvnw);
            }

            Path mvnwCmd = root.toPath().resolve("mvnw.cmd");
            if (!mvnwCmd.toFile().exists()) {
                writeMvnwCmdScript(mvnwCmd);
            }

            return true;
        } catch (IOException e) {
            getLog().warn("    Could not install workspace root Maven wrapper: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensure the Maven wrapper is installed and points to the correct version.
     * Writes {@code .mvn/wrapper/maven-wrapper.properties} and the
     * {@code mvnw} / {@code mvnw.cmd} launcher scripts.
     *
     * <p>Skips if no maven-version is configured for this component.
     * Updates the properties file if the version has changed (e.g., after
     * a branch switch updates workspace.yaml).
     *
     * @param componentDir the component root directory
     * @param component    the component definition
     * @param defaults     workspace defaults
     * @return true if wrapper was installed or updated
     */
    private boolean ensureMavenWrapper(File componentDir, Component component,
                                        Defaults defaults) {
        String mavenVersion = resolveMavenVersion(component, defaults);
        if (mavenVersion == null) {
            return false;
        }

        // Only install wrapper if the component has a pom.xml (it's a Maven project)
        File pomFile = new File(componentDir, "pom.xml");
        if (!pomFile.exists()) {
            return false;
        }

        try {
            Path wrapperDir = componentDir.toPath().resolve(".mvn").resolve("wrapper");
            Path propsFile = wrapperDir.resolve("maven-wrapper.properties");

            // Check if already at the correct version
            if (propsFile.toFile().exists()) {
                Properties existing = new Properties();
                try (var reader = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
                    existing.load(reader);
                }
                String currentVersion = existing.getProperty("maven.version");
                if (mavenVersion.equals(currentVersion)) {
                    getLog().debug("    Maven wrapper already at " + mavenVersion);
                    return false;
                }
                getLog().info("    Updating Maven wrapper: " + currentVersion
                        + " → " + mavenVersion);
            } else {
                getLog().info("    Installing Maven wrapper for Maven " + mavenVersion);
            }

            // Create .mvn/wrapper/ directory
            Files.createDirectories(wrapperDir);

            // Write maven-wrapper.properties
            String props = "# Maven Wrapper properties — managed by ike:init from workspace.yaml\n"
                    + "maven.version=" + mavenVersion + "\n"
                    + "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
                    + "apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion
                    + "-bin.zip\n";
            Files.writeString(propsFile, props, StandardCharsets.UTF_8);

            // Write mvnw launcher script (Unix)
            Path mvnw = componentDir.toPath().resolve("mvnw");
            if (!mvnw.toFile().exists()) {
                writeMvnwScript(mvnw);
            }

            // Write mvnw.cmd launcher script (Windows)
            Path mvnwCmd = componentDir.toPath().resolve("mvnw.cmd");
            if (!mvnwCmd.toFile().exists()) {
                writeMvnwCmdScript(mvnwCmd);
            }

            return true;
        } catch (IOException e) {
            getLog().warn("    Could not install Maven wrapper: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write the Unix mvnw launcher script. This is the standard Maven Wrapper
     * bootstrap script that downloads the correct Maven version on first use.
     */
    private void writeMvnwScript(Path mvnw) throws IOException {
        String script = """
                #!/bin/sh
                # Maven Wrapper launcher — installed by ike:init
                # Downloads and caches the Maven version specified in
                # .mvn/wrapper/maven-wrapper.properties
                #
                # This is a minimal bootstrap. For the full-featured wrapper script,
                # run: mvn wrapper:wrapper

                set -e

                PROPS_FILE="$(dirname "$0")/.mvn/wrapper/maven-wrapper.properties"
                if [ ! -f "$PROPS_FILE" ]; then
                    echo "Error: $PROPS_FILE not found" >&2
                    exit 1
                fi

                DIST_URL=$(grep '^distributionUrl=' "$PROPS_FILE" | cut -d'=' -f2-)
                MAVEN_VERSION=$(grep '^maven.version=' "$PROPS_FILE" | cut -d'=' -f2-)

                WRAPPER_HOME="${HOME}/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
                MAVEN_HOME="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}"

                if [ ! -d "$MAVEN_HOME" ]; then
                    echo "Downloading Maven ${MAVEN_VERSION}..."
                    mkdir -p "$WRAPPER_HOME"
                    ZIP_FILE="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}-bin.zip"
                    curl -fsSL -o "$ZIP_FILE" "$DIST_URL"
                    unzip -qo "$ZIP_FILE" -d "$WRAPPER_HOME"
                    rm -f "$ZIP_FILE"
                    echo "Maven ${MAVEN_VERSION} installed to ${MAVEN_HOME}"
                fi

                exec "$MAVEN_HOME/bin/mvn" "$@"
                """;
        Files.writeString(mvnw, script, StandardCharsets.UTF_8);
        mvnw.toFile().setExecutable(true);
    }

    /**
     * Write the Windows mvnw.cmd launcher script.
     */
    private void writeMvnwCmdScript(Path mvnwCmd) throws IOException {
        String script = """
                @REM Maven Wrapper launcher — installed by ike:init
                @REM Downloads and caches the Maven version specified in
                @REM .mvn/wrapper/maven-wrapper.properties
                @echo off
                setlocal

                set "PROPS_FILE=%~dp0.mvn\\wrapper\\maven-wrapper.properties"
                if not exist "%PROPS_FILE%" (
                    echo Error: %PROPS_FILE% not found >&2
                    exit /b 1
                )

                for /f "tokens=1,* delims==" %%a in ('findstr "^maven.version=" "%PROPS_FILE%"') do set "MAVEN_VERSION=%%b"
                for /f "tokens=1,* delims==" %%a in ('findstr "^distributionUrl=" "%PROPS_FILE%"') do set "DIST_URL=%%b"

                set "WRAPPER_HOME=%USERPROFILE%\\.m2\\wrapper\\dists\\apache-maven-%MAVEN_VERSION%"
                set "MAVEN_HOME=%WRAPPER_HOME%\\apache-maven-%MAVEN_VERSION%"

                if not exist "%MAVEN_HOME%" (
                    echo Downloading Maven %MAVEN_VERSION%...
                    mkdir "%WRAPPER_HOME%" 2>nul
                    set "ZIP_FILE=%WRAPPER_HOME%\\apache-maven-%MAVEN_VERSION%-bin.zip"
                    powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
                    powershell -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%WRAPPER_HOME%' -Force"
                    del "%ZIP_FILE%"
                    echo Maven %MAVEN_VERSION% installed to %MAVEN_HOME%
                )

                "%MAVEN_HOME%\\bin\\mvn.cmd" %*
                """;
        Files.writeString(mvnwCmd, script, StandardCharsets.UTF_8);
    }

    /**
     * Ensure {@code .mvn/jvm.config} exists with standard JVM flags.
     * Only applies to Maven projects (must have a {@code pom.xml}).
     * Does not overwrite an existing file.
     */
    private void ensureJvmConfig(File componentDir) {
        File pomFile = new File(componentDir, "pom.xml");
        if (!pomFile.exists()) {
            return;
        }

        try {
            Path mvnDir = componentDir.toPath().resolve(".mvn");
            Path jvmConfig = mvnDir.resolve("jvm.config");

            if (jvmConfig.toFile().exists()) {
                return;
            }

            Files.createDirectories(mvnDir);
            String config = "-Dpolyglotimpl.AttachLibraryFailureAction=ignore\n";
            Files.writeString(jvmConfig, config, StandardCharsets.UTF_8);
            getLog().info("    Created .mvn/jvm.config");
        } catch (IOException e) {
            getLog().warn("    Could not create jvm.config: " + e.getMessage());
        }
    }

    /**
     * Install defensive git hooks in the component's .git/hooks/ directory.
     * Skips if the hook already exists (don't overwrite custom hooks).
     */
    private void installHooks(File componentDir) {
        File hooksDir = new File(componentDir, ".git/hooks");
        File postCheckout = new File(hooksDir, "post-checkout");

        if (postCheckout.exists()) {
            getLog().debug("  Hook already exists: " + postCheckout);
            return;
        }

        try {
            if (!hooksDir.exists()) {
                hooksDir.mkdirs();
            }
            String hookScript = "#!/bin/sh\n"
                    + "# Installed by ike:init \u2014 warns on direct branching.\n"
                    + "# Remove this file to disable the check.\n"
                    + "mvn -q ike:check-branch 2>/dev/null\n";
            java.nio.file.Files.writeString(postCheckout.toPath(), hookScript,
                    java.nio.charset.StandardCharsets.UTF_8);
            postCheckout.setExecutable(true);
            getLog().info("    Installed post-checkout hook");
        } catch (java.io.IOException e) {
            getLog().warn("    Could not install hook: " + e.getMessage());
        }
    }
}
