package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Upgrade workspace conventions to the current plugin version.
 *
 * <p>As workspace conventions evolve across plugin releases, this goal
 * applies incremental upgrades to bring an existing workspace in line
 * with current standards. Each upgrade step is idempotent — running
 * the goal twice produces the same result.
 *
 * <h2>Current upgrade steps</h2>
 * <ul>
 *   <li><b>gitignore-vcs-state</b> — ensure {@code .ike/vcs-state}
 *       is in the global gitignore (VCS bridge coordination file
 *       should be synced by Syncthing, not tracked by git)</li>
 *   <li><b>gitignore-whitelist</b> — ensure workspace .gitignore
 *       uses the whitelist strategy and includes all standard entries</li>
 *   <li><b>stignore-delete-flag</b> — ensure {@code stignore-shared}
 *       uses {@code (?d)} prefix on directory ignore patterns</li>
 *   <li><b>pom-root-attribute</b> — ensure workspace POM has
 *       {@code root="true"} for Maven 4.1.0 reactor boundary</li>
 *   <li><b>maven-config</b> — ensure {@code .mvn/maven.config} exists</li>
 *   <li><b>plugin-version</b> — update {@code ike-maven-plugin.version}
 *       property in the workspace POM to the current plugin version</li>
 * </ul>
 *
 * <pre>{@code
 * mvn ike:ws-upgrade              # apply all upgrades
 * mvn ike:ws-upgrade -DdryRun     # preview what would change
 * }</pre>
 *
 * @see WsCreateMojo for creating a new workspace
 */
@Mojo(name = "ws-upgrade", requiresProject = false, threadSafe = true)
public class WsUpgradeMojo extends AbstractWorkspaceMojo {

    /**
     * Show what would change without modifying any files.
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    /** Creates this goal instance. */
    public WsUpgradeMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        File root = workspaceRoot();
        Path rootPath = root.toPath();

        String pluginVersion = getClass().getPackage().getImplementationVersion();
        if (pluginVersion == null) pluginVersion = "49";

        getLog().info("");
        getLog().info("IKE Workspace — Upgrade");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Workspace: " + root.getName());
        getLog().info("  Plugin:    " + pluginVersion);
        if (dryRun) {
            getLog().info("  Mode:      DRY RUN");
        }
        getLog().info("");

        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // ── 1. Global gitignore: .ike/vcs-state ─────────────────
        upgradeGlobalGitignore(applied, skipped);

        // ── 2. Workspace .gitignore whitelist ───────────────────
        upgradeWorkspaceGitignore(rootPath, applied, skipped);

        // ── 3. stignore-shared (?d) flags ───────────────────────
        upgradeStignoreShared(rootPath, applied, skipped);

        // ── 4. POM root="true" ──────────────────────────────────
        upgradePomRoot(rootPath, applied, skipped);

        // ── 5. .mvn/maven.config ────────────────────────────────
        upgradeMavenConfig(rootPath, applied, skipped);

        // ── 6. Plugin version ───────────────────────────────────
        upgradePluginVersion(rootPath, pluginVersion, applied, skipped);

        // ── Summary ─────────────────────────────────────────────
        getLog().info("");
        getLog().info("  " + applied.size() + " upgrade(s) applied, "
                + skipped.size() + " already current.");
        if (dryRun && !applied.isEmpty()) {
            getLog().info("  (DRY RUN — no files modified)");
        }
        getLog().info("");
    }

    // ── Upgrade steps ────────────────────────────────────────────

    private void upgradeGlobalGitignore(List<String> applied, List<String> skipped) {
        Path globalIgnore = Path.of(System.getProperty("user.home"), ".gitignore_global");
        try {
            String content = Files.exists(globalIgnore)
                    ? Files.readString(globalIgnore, StandardCharsets.UTF_8) : "";

            boolean needsVcsState = !content.contains("vcs-state");
            boolean needsGitInit = !content.contains("_git-init");

            if (!needsVcsState && !needsGitInit) {
                skipped.add("global-gitignore");
                getLog().info("  ✓ Global gitignore: already current");
                return;
            }

            StringBuilder additions = new StringBuilder();
            if (needsGitInit) additions.append("_git-init*\n");
            if (needsVcsState) additions.append(".ike/vcs-state\n");

            if (!dryRun) {
                Files.writeString(globalIgnore,
                        content + (content.endsWith("\n") ? "" : "\n") + additions,
                        StandardCharsets.UTF_8);
            }

            applied.add("global-gitignore");
            getLog().info("  ↑ Global gitignore: added "
                    + (needsVcsState ? ".ike/vcs-state " : "")
                    + (needsGitInit ? "_git-init* " : ""));
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not update global gitignore: " + e.getMessage());
        }
    }

    private void upgradeWorkspaceGitignore(Path root, List<String> applied,
                                            List<String> skipped) {
        Path gitignore = root.resolve(".gitignore");
        try {
            if (!Files.exists(gitignore)) {
                skipped.add("workspace-gitignore");
                getLog().info("  - Workspace .gitignore: not present (skipped)");
                return;
            }

            String content = Files.readString(gitignore, StandardCharsets.UTF_8);
            boolean changed = false;

            // Ensure whitelist entries exist
            String[] required = {"!.gitignore", "!pom.xml", "!workspace.yaml",
                    "!.mvn/", "!.mvn/**", "!checkpoints/", "!checkpoints/**"};
            StringBuilder additions = new StringBuilder();
            for (String entry : required) {
                if (!content.contains(entry)) {
                    additions.append(entry).append("\n");
                    changed = true;
                }
            }

            if (!changed) {
                skipped.add("workspace-gitignore");
                getLog().info("  ✓ Workspace .gitignore: already current");
                return;
            }

            if (!dryRun) {
                Files.writeString(gitignore,
                        content + (content.endsWith("\n") ? "" : "\n") + additions,
                        StandardCharsets.UTF_8);
            }
            applied.add("workspace-gitignore");
            getLog().info("  ↑ Workspace .gitignore: added missing whitelist entries");
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not update .gitignore: " + e.getMessage());
        }
    }

    private void upgradeStignoreShared(Path root, List<String> applied,
                                        List<String> skipped) {
        // Look for stignore-shared in ike-dev root (parent of workspace)
        Path stignore = root.getParent() != null
                ? root.getParent().resolve("stignore-shared") : null;
        if (stignore == null || !Files.exists(stignore)) {
            skipped.add("stignore-shared");
            getLog().info("  - stignore-shared: not found (Syncthing not configured?)");
            return;
        }

        try {
            String content = Files.readString(stignore, StandardCharsets.UTF_8);
            boolean changed = false;
            String updated = content;

            // Upgrade patterns that lack (?d) prefix
            String[][] upgrades = {
                    {"\n.git/", "\n(?d).git/"},
                    {"\ntarget/", "\n(?d)target/"},
                    {"\nout/", "\n(?d)out/"},
                    {"\n.gradle/", "\n(?d).gradle/"},
                    {"\nbuild/", "\n(?d)build/"},
            };

            for (String[] pair : upgrades) {
                if (updated.contains(pair[0]) && !updated.contains(pair[1])) {
                    updated = updated.replace(pair[0], pair[1]);
                    changed = true;
                }
            }
            // Handle first line (no leading newline)
            if (updated.startsWith(".git/") && !updated.startsWith("(?d).git/")) {
                updated = "(?d)" + updated;
                changed = true;
            }

            if (!changed) {
                skipped.add("stignore-shared");
                getLog().info("  ✓ stignore-shared: (?d) flags already present");
                return;
            }

            if (!dryRun) {
                Files.writeString(stignore, updated, StandardCharsets.UTF_8);
            }
            applied.add("stignore-shared");
            getLog().info("  ↑ stignore-shared: added (?d) prefix to directory patterns");
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not update stignore-shared: " + e.getMessage());
        }
    }

    private void upgradePomRoot(Path root, List<String> applied,
                                 List<String> skipped) {
        Path pom = root.resolve("pom.xml");
        try {
            if (!Files.exists(pom)) {
                skipped.add("pom-root");
                return;
            }

            String content = Files.readString(pom, StandardCharsets.UTF_8);
            if (content.contains("root=\"true\"")) {
                skipped.add("pom-root");
                getLog().info("  ✓ POM root attribute: already present");
                return;
            }

            // Add root="true" to <project> element
            String updated = content.replaceFirst(
                    "(<project\\s[^>]*?)(>)",
                    "$1\n         root=\"true\"$2");

            if (!dryRun) {
                Files.writeString(pom, updated, StandardCharsets.UTF_8);
            }
            applied.add("pom-root");
            getLog().info("  ↑ POM: added root=\"true\"");
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not update pom.xml: " + e.getMessage());
        }
    }

    private void upgradeMavenConfig(Path root, List<String> applied,
                                     List<String> skipped) {
        Path config = root.resolve(".mvn/maven.config");
        try {
            if (Files.exists(config)) {
                skipped.add("maven-config");
                getLog().info("  ✓ .mvn/maven.config: already present");
                return;
            }

            if (!dryRun) {
                Files.createDirectories(config.getParent());
                Files.writeString(config, "-T 1C\n", StandardCharsets.UTF_8);
            }
            applied.add("maven-config");
            getLog().info("  ↑ .mvn/maven.config: created with -T 1C");
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not create .mvn/maven.config: " + e.getMessage());
        }
    }

    private void upgradePluginVersion(Path root, String pluginVersion,
                                       List<String> applied, List<String> skipped) {
        Path pom = root.resolve("pom.xml");
        try {
            if (!Files.exists(pom)) {
                skipped.add("plugin-version");
                return;
            }

            String content = Files.readString(pom, StandardCharsets.UTF_8);

            // Find current ike-maven-plugin.version property
            java.util.regex.Pattern versionProp = java.util.regex.Pattern.compile(
                    "(<ike-maven-plugin\\.version>)(.*?)(</ike-maven-plugin\\.version>)");
            java.util.regex.Matcher m = versionProp.matcher(content);

            if (!m.find()) {
                skipped.add("plugin-version");
                getLog().info("  - Plugin version: no ike-maven-plugin.version property found");
                return;
            }

            String currentVersion = m.group(2);
            if (currentVersion.equals(pluginVersion)) {
                skipped.add("plugin-version");
                getLog().info("  ✓ Plugin version: already " + pluginVersion);
                return;
            }

            if (!dryRun) {
                String updated = m.replaceFirst("$1" + pluginVersion + "$3");
                Files.writeString(pom, updated, StandardCharsets.UTF_8);
            }
            applied.add("plugin-version");
            getLog().info("  ↑ Plugin version: " + currentVersion + " → " + pluginVersion);
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not update plugin version: " + e.getMessage());
        }
    }
}
