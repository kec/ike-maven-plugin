package network.ike.plugin;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generate a Bill of Materials POM from another module's dependency management.
 *
 * <p>Reads the {@code <dependencyManagement>} entries from a source module
 * (default: {@code ike-parent}) in the reactor and writes a standalone BOM
 * POM with all versions resolved to literals. The generated POM replaces
 * the stub POM for install/deploy, so external consumers get a fully
 * populated BOM without any manual maintenance.</p>
 *
 * <p>Bind this goal to a POM-packaged stub module in the reactor,
 * ordered <em>after</em> the source module and the plugin module:</p>
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;network.ike&lt;/groupId&gt;
 *   &lt;artifactId&gt;ike-maven-plugin&lt;/artifactId&gt;
 *   &lt;executions&gt;
 *     &lt;execution&gt;
 *       &lt;id&gt;generate-bom&lt;/id&gt;
 *       &lt;goals&gt;&lt;goal&gt;generate-bom&lt;/goal&gt;&lt;/goals&gt;
 *     &lt;/execution&gt;
 *   &lt;/executions&gt;
 * &lt;/plugin&gt;
 * </pre>
 */
@Mojo(name = "generate-bom",
      defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
      requiresProject = true,
      threadSafe = true)
public class GenerateBomMojo extends AbstractMojo {

    /** The current project (injected by Maven). */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Reactor projects (injected by Maven). */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    /**
     * Artifact ID of the reactor module whose {@code <dependencyManagement>}
     * entries should be copied into the generated BOM.
     */
    @Parameter(property = "bom.source", defaultValue = "ike-parent")
    private String sourceArtifactId;

    @Override
    public void execute() throws MojoExecutionException {
        // ── Find source module in reactor ────────────────────────────
        MavenProject source = reactorProjects.stream()
                .filter(p -> p.getArtifactId().equals(sourceArtifactId))
                .findFirst()
                .orElseThrow(() -> new MojoExecutionException(
                        sourceArtifactId + " not found in reactor. "
                        + "Ensure it is listed before this module in <subprojects>."));

        DependencyManagement depMgmt = source.getDependencyManagement();
        if (depMgmt == null || depMgmt.getDependencies().isEmpty()) {
            throw new MojoExecutionException(
                    sourceArtifactId + " has no <dependencyManagement> entries.");
        }

        List<Dependency> deps = depMgmt.getDependencies();

        // ── Convert Maven Dependency objects to BomEntry records ─────
        List<BomEntry> entries = deps.stream()
                .map(dep -> new BomEntry(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
                        dep.getClassifier(), dep.getType(), dep.getScope()))
                .toList();

        // ── Generate BOM POM ─────────────────────────────────────────
        String bomXml = buildBomXml(
                project.getGroupId(), project.getArtifactId(), project.getVersion(),
                project.getName(), project.getDescription(), project.getUrl(),
                entries);

        Path targetDir = Path.of(project.getBuild().getDirectory());
        try {
            Files.createDirectories(targetDir);
            Path bomPom = targetDir.resolve("generated-bom.xml");
            Files.writeString(bomPom, bomXml);

            // Replace the project POM so install/deploy uses the
            // generated BOM instead of the stub.
            project.setPomFile(bomPom.toFile());

            getLog().info("Generated BOM with " + deps.size()
                    + " managed entries from " + sourceArtifactId);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write generated BOM", e);
        }
    }

    // ── XML generation (pure, static, testable) ─────────────────────

    /**
     * Build a complete BOM POM XML string from the given project
     * coordinates and dependency entries.
     *
     * <p>This is a pure function with no Maven or I/O dependencies,
     * suitable for direct unit testing.
     *
     * @param groupId     project group ID
     * @param artifactId  project artifact ID
     * @param version     project version
     * @param name        project display name (XML-escaped internally)
     * @param description project description (may be null)
     * @param url         project URL (may be null)
     * @param entries     managed dependency entries
     * @return well-formed POM XML
     */
    public static String buildBomXml(String groupId, String artifactId,
                                      String version, String name,
                                      String description, String url,
                                      List<BomEntry> entries) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!--\n");
        xml.append("  Auto-generated BOM — do not edit.\n");
        xml.append("  Generated by: ike:generate-bom\n");
        xml.append("-->\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n");
        xml.append("         http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        xml.append("    <modelVersion>4.0.0</modelVersion>\n\n");

        xml.append("    <groupId>").append(groupId).append("</groupId>\n");
        xml.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        xml.append("    <version>").append(version).append("</version>\n");
        xml.append("    <packaging>pom</packaging>\n\n");

        xml.append("    <name>").append(escapeXml(name)).append("</name>\n");
        if (description != null) {
            xml.append("    <description>")
               .append(escapeXml(description.strip()))
               .append("</description>\n");
        }
        xml.append("    <url>").append(url != null ? url : "").append("</url>\n\n");

        // Dependency Management
        xml.append("    <dependencyManagement>\n");
        xml.append("        <dependencies>\n");

        for (BomEntry entry : entries) {
            xml.append("            <dependency>\n");
            xml.append("                <groupId>").append(entry.groupId()).append("</groupId>\n");
            xml.append("                <artifactId>").append(entry.artifactId()).append("</artifactId>\n");
            xml.append("                <version>").append(entry.version()).append("</version>\n");

            if (entry.classifier() != null && !entry.classifier().isEmpty()) {
                xml.append("                <classifier>").append(entry.classifier()).append("</classifier>\n");
            }
            if (entry.type() != null && !"jar".equals(entry.type())) {
                xml.append("                <type>").append(entry.type()).append("</type>\n");
            }
            if (entry.scope() != null && !"compile".equals(entry.scope())) {
                xml.append("                <scope>").append(entry.scope()).append("</scope>\n");
            }

            xml.append("            </dependency>\n");
        }

        xml.append("        </dependencies>\n");
        xml.append("    </dependencyManagement>\n");
        xml.append("</project>\n");

        return xml.toString();
    }

    /**
     * Escape XML special characters in text content.
     *
     * @param text input text (may be null)
     * @return escaped text, or empty string if null
     */
    public static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}
