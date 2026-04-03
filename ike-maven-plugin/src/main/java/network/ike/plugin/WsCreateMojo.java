package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Create a new IKE workspace from scratch.
 *
 * <p>Generates the standard workspace scaffolding in the current
 * directory (or a named subdirectory): reactor POM, workspace.yaml,
 * .gitignore, .mvn/maven.config, and a README.adoc. Optionally
 * initializes git and sets the remote.
 *
 * <p>The generated files follow current IKE conventions:
 * <ul>
 *   <li>POM uses Maven 4.1.0 model with {@code root="true"}</li>
 *   <li>.gitignore uses whitelist strategy (ignore everything,
 *       whitelist workspace-owned files)</li>
 *   <li>workspace.yaml has schema-version 1.0 with empty components</li>
 *   <li>.mvn/maven.config sets {@code -T 1C}</li>
 * </ul>
 *
 * <p>After creation, use {@code ike:ws-add} to add component repos,
 * then {@code ike:init} to clone them.
 *
 * <pre>{@code
 * mvn ike:ws-create -Dname=my-workspace
 * mvn ike:ws-create -Dname=my-workspace -Dorg=knowledge-graphlet
 * }</pre>
 *
 * @see WsAddMojo for adding components to an existing workspace
 * @see WsUpgradeMojo for upgrading workspace conventions
 */
@Mojo(name = "ws-create", requiresProject = false, threadSafe = true)
public class WsCreateMojo extends AbstractMojo {

    /**
     * Workspace name. Used as the directory name, Maven artifactId,
     * and in generated documentation. Prompted if omitted.
     */
    @Parameter(property = "name")
    private String name;

    /**
     * Short description of the workspace purpose.
     */
    @Parameter(property = "description", defaultValue = "IKE workspace")
    private String description;

    /**
     * GitHub organization or user for the remote URL.
     * If set, the remote is configured as
     * {@code https://github.com/<org>/<name>.git}.
     */
    @Parameter(property = "org")
    private String org;

    /**
     * Default Maven version for components. Written to
     * {@code defaults.maven-version} in workspace.yaml.
     */
    @Parameter(property = "mavenVersion", defaultValue = "4.0.0-rc-5")
    private String mavenVersion;

    /**
     * Default branch for components. Written to
     * {@code defaults.branch} in workspace.yaml.
     */
    @Parameter(property = "branch", defaultValue = "main")
    private String defaultBranch;

    /**
     * Skip git init and remote setup.
     */
    @Parameter(property = "skipGit", defaultValue = "false")
    private boolean skipGit;

    /** Creates this goal instance. */
    public WsCreateMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (name == null || name.isBlank()) {
            name = promptParam("name", "Workspace name");
        }

        Path wsDir = Path.of(System.getProperty("user.dir")).resolve(name);

        getLog().info("");
        getLog().info("IKE Workspace — Create");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Name:      " + name);
        getLog().info("  Directory: " + wsDir);
        if (org != null && !org.isBlank()) {
            getLog().info("  Remote:    https://github.com/" + org + "/" + name + ".git");
        }
        getLog().info("");

        try {
            Files.createDirectories(wsDir);
            Files.createDirectories(wsDir.resolve(".mvn"));

            writeFile(wsDir.resolve("pom.xml"), generatePom());
            writeFile(wsDir.resolve("workspace.yaml"), generateManifest());
            writeFile(wsDir.resolve(".gitignore"), generateGitignore());
            writeFile(wsDir.resolve(".mvn/maven.config"), "-T 1C\n");
            writeFile(wsDir.resolve("README.adoc"), generateReadme());
            installMavenWrapper(wsDir);

            getLog().info("  ✓ pom.xml");
            getLog().info("  ✓ workspace.yaml");
            getLog().info("  ✓ .gitignore");
            getLog().info("  ✓ .mvn/maven.config");
            getLog().info("  ✓ README.adoc");
            getLog().info("  ✓ mvnw (Maven " + mavenVersion + ")");

        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to create workspace files: " + e.getMessage(), e);
        }

        // Git init
        if (!skipGit) {
            try {
                initGit(wsDir);
            } catch (Exception e) {
                getLog().warn("  Git init failed (non-fatal): " + e.getMessage());
                getLog().warn("  Initialize git manually in " + wsDir);
            }
        }

        getLog().info("");
        getLog().info("  Workspace created: " + wsDir);
        getLog().info("");
        getLog().info("  Next steps:");
        getLog().info("    cd " + name);
        getLog().info("    mvn ike:ws-add -Drepo=<git-url>    # add components");
        getLog().info("    mvn ike:init                        # clone components");
        getLog().info("");
    }

    // ── File generators (pure, testable) ─────────────────────────

    String generatePom() {
        String pluginVersion = getClass().getPackage().getImplementationVersion();
        if (pluginVersion == null) pluginVersion = "49";

        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!--\n");
        xml.append("  ").append(description).append("\n");
        xml.append("\n");
        xml.append("  Every subproject is inside a file-activated profile so the reactor\n");
        xml.append("  automatically includes only the repos that are physically cloned.\n");
        xml.append("  Clone more repos and they join the reactor on the next build.\n");
        xml.append("\n");
        xml.append("  Usage:\n");
        xml.append("    mvn clean install                        # All cloned repos\n");
        xml.append("    mvn ike:init                             # Clone all repos\n");
        xml.append("    mvn ike:status                           # Git status across repos\n");
        xml.append("    mvn ike:dashboard                        # Full workspace overview\n");
        xml.append("-->\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.1.0\"\n");
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.1.0\n");
        xml.append("                             https://maven.apache.org/xsd/maven-4.1.0.xsd\"\n");
        xml.append("         root=\"true\">\n");
        xml.append("    <modelVersion>4.1.0</modelVersion>\n\n");
        xml.append("    <groupId>local.aggregate</groupId>\n");
        xml.append("    <artifactId>").append(name).append("</artifactId>\n");
        xml.append("    <version>1.0.0-SNAPSHOT</version>\n");
        xml.append("    <packaging>pom</packaging>\n\n");
        xml.append("    <name>").append(description).append("</name>\n\n");
        xml.append("    <properties>\n");
        xml.append("        <ike-tooling.version>").append(pluginVersion).append("</ike-tooling.version>\n");
        xml.append("    </properties>\n\n");
        xml.append("    <build>\n");
        xml.append("        <plugins>\n");
        xml.append("            <plugin>\n");
        xml.append("                <groupId>network.ike</groupId>\n");
        xml.append("                <artifactId>ike-maven-plugin</artifactId>\n");
        xml.append("                <version>${ike-tooling.version}</version>\n");
        xml.append("            </plugin>\n");
        xml.append("        </plugins>\n");
        xml.append("    </build>\n\n");
        xml.append("    <!-- Profiles are added by ike:ws-add -->\n");
        xml.append("    <profiles>\n");
        xml.append("    </profiles>\n\n");
        xml.append("</project>\n");
        return xml.toString();
    }

    String generateManifest() {
        String today = LocalDate.now().toString();
        String orgName = org != null ? org : "<org>";

        StringBuilder yaml = new StringBuilder(1024);
        yaml.append("# workspace.yaml — ").append(name).append("\n");
        yaml.append("# ").append("═".repeat(name.length() + 22)).append("\n");
        yaml.append("#\n");
        yaml.append("# ").append(description).append("\n");
        yaml.append("#\n");
        yaml.append("# Bootstrap:\n");
        yaml.append("#   git clone https://github.com/").append(orgName).append("/").append(name).append(".git\n");
        yaml.append("#   cd ").append(name).append("\n");
        yaml.append("#   mvn ike:init\n");
        yaml.append("#   mvn clean install\n\n");
        yaml.append("schema-version: \"1.0\"\n");
        yaml.append("generated: ").append(today).append("\n\n");
        yaml.append("defaults:\n");
        yaml.append("  branch: ").append(defaultBranch).append("\n");
        yaml.append("  maven-version: \"").append(mavenVersion).append("\"\n\n");
        yaml.append("component-types:\n");
        yaml.append("  software:\n");
        yaml.append("    description: \"Java libraries and applications\"\n");
        yaml.append("    build-command: \"mvn clean install\"\n");
        yaml.append("    checkpoint-mechanism: git-tag\n\n");
        yaml.append("components:\n");
        yaml.append("  # Add components with: mvn ike:ws-add -Drepo=<git-url>\n\n");
        yaml.append("groups:\n");
        yaml.append("  all: []\n");
        return yaml.toString();
    }

    String generateGitignore() {
        StringBuilder gi = new StringBuilder(512);
        gi.append("# ").append(name).append(" .gitignore\n");
        gi.append("# ").append("═".repeat(name.length() + 11)).append("\n");
        gi.append("#\n");
        gi.append("# Ignore everything, whitelist only workspace-owned files.\n");
        gi.append("# Component repos are independent git repos cloned by ike:init.\n\n");
        gi.append("# ── Ignore everything by default ─────────────────────────────────\n");
        gi.append("*\n\n");
        gi.append("# ── Whitelist workspace-level files ──────────────────────────────\n");
        gi.append("!.gitignore\n");
        gi.append("!pom.xml\n");
        gi.append("!workspace.yaml\n");
        gi.append("!README.adoc\n");
        gi.append("!mvnw\n");
        gi.append("!mvnw.cmd\n\n");
        gi.append("# ── Whitelist workspace-owned directories ────────────────────────\n");
        gi.append("!.mvn/\n");
        gi.append("!.mvn/**\n");
        gi.append("!checkpoints/\n");
        gi.append("!checkpoints/**\n");
        gi.append("!.run/\n");
        gi.append("!.run/**\n");
        return gi.toString();
    }

    String generateReadme() {
        String orgName = org != null ? org : "<org>";

        StringBuilder adoc = new StringBuilder(1024);
        adoc.append("= ").append(description).append("\n");
        adoc.append(":toc:\n");
        adoc.append(":toc-placement!:\n\n");
        adoc.append(description).append("\n\n");
        adoc.append("toc::[]\n\n");
        adoc.append("== Bootstrap\n\n");
        adoc.append("[source,bash]\n");
        adoc.append("----\n");
        adoc.append("git clone https://github.com/").append(orgName).append("/").append(name).append(".git\n");
        adoc.append("cd ").append(name).append("\n");
        adoc.append("mvn ike:init        # <1>\n");
        adoc.append("mvn clean install   # <2>\n");
        adoc.append("----\n");
        adoc.append("<1> Clones all component repos in dependency order; installs Maven\n");
        adoc.append("    wrapper and JVM config per component.\n");
        adoc.append("<2> Builds the full stack.\n\n");
        adoc.append("== Workspace Commands\n\n");
        adoc.append("All `ike:` goals appear in the IntelliJ Maven tool window\n");
        adoc.append("(under _Plugins > ike_). Double-click any goal to run it.\n\n");
        adoc.append("[source,bash]\n");
        adoc.append("----\n");
        adoc.append("mvn ike:status          # Git status across components\n");
        adoc.append("mvn ike:dashboard       # Full workspace health check\n");
        adoc.append("mvn ike:ws-add -Drepo=  # Add a component repo\n");
        adoc.append("mvn ike:ws-upgrade      # Upgrade workspace conventions\n");
        adoc.append("----\n");
        return adoc.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void initGit(Path wsDir) throws MojoExecutionException {
        ReleaseSupport.exec(wsDir.toFile(), getLog(),
                "git", "init");
        getLog().info("  ✓ git init");

        if (org != null && !org.isBlank()) {
            String remoteUrl = "https://github.com/" + org + "/" + name + ".git";
            ReleaseSupport.exec(wsDir.toFile(), getLog(),
                    "git", "remote", "add", "origin", remoteUrl);
            getLog().info("  ✓ remote: " + remoteUrl);
        }
    }

    /**
     * Install Maven wrapper (mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties)
     * at the workspace root so the aggregator POM resolves the correct Maven version.
     */
    private void installMavenWrapper(Path wsDir) throws IOException {
        Path wrapperDir = wsDir.resolve(".mvn").resolve("wrapper");
        Files.createDirectories(wrapperDir);

        String props = "# Maven Wrapper properties — managed by ike:init from workspace.yaml\n"
                + "maven.version=" + mavenVersion + "\n"
                + "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
                + "apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion
                + "-bin.zip\n";
        Files.writeString(wrapperDir.resolve("maven-wrapper.properties"), props,
                StandardCharsets.UTF_8);

        Path mvnw = wsDir.resolve("mvnw");
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

        Path mvnwCmd = wsDir.resolve("mvnw.cmd");
        String cmdScript = """
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
        Files.writeString(mvnwCmd, cmdScript, StandardCharsets.UTF_8);
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String promptParam(String propertyName, String label)
            throws MojoExecutionException {
        java.io.Console console = System.console();
        if (console != null) {
            String input = console.readLine(label + ": ");
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }
        throw new MojoExecutionException(
                propertyName + " is required. Specify -D" + propertyName + "=<value>");
    }
}
