package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package AsciiDoc sources as the primary artifact for {@code ike-doc} packaging.
 *
 * <p>Zips the contents of {@code src/docs/asciidoc/} into a {@code .zip}
 * file and sets it as the project's primary artifact. This makes the
 * AsciiDoc source the canonical artifact — rendered outputs (HTML, PDF)
 * are attached as classifier artifacts by the rendering pipeline.
 *
 * <p>This goal is bound to the {@code package} phase in the
 * {@code ike-doc} lifecycle mapping. It can also be invoked directly:
 *
 * <pre>
 * mvn ike:package-doc
 * </pre>
 */
@Mojo(name = "package-doc",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresProject = true,
      threadSafe = true)
public class PackageDocMojo extends AbstractMojo {

    /** The current project (injected by Maven). */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * AsciiDoc source directory to package.
     * Defaults to the standard IKE documentation source location.
     */
    @Parameter(property = "ike.doc.sourceDir",
               defaultValue = "${project.basedir}/src/docs/asciidoc")
    private File sourceDir;

    /** Skip execution. */
    @Parameter(property = "ike.doc.skip", defaultValue = "false")
    private boolean skip;

    /** Creates this goal instance. */
    public PackageDocMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("package-doc: skipped");
            return;
        }

        if (!sourceDir.isDirectory()) {
            getLog().warn("package-doc: source directory does not exist — "
                    + sourceDir + ". Producing empty artifact.");
        }

        Path source = sourceDir.toPath();
        String finalName = project.getBuild().getFinalName();
        Path outputDir = Path.of(project.getBuild().getDirectory());
        Path zipFile = outputDir.resolve(finalName + ".zip");

        try {
            Files.createDirectories(outputDir);
            int count = zipDirectory(source, zipFile);
            getLog().info("package-doc: packaged " + count
                    + " file(s) into " + zipFile.getFileName());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to create documentation archive", e);
        }

        project.getArtifact().setFile(zipFile.toFile());
    }

    // ── Pure testable function ───────────────────────────────────────

    /**
     * Zip the contents of a directory into a zip file.
     *
     * <p>If the source directory does not exist or is empty, an empty
     * zip file is produced (valid for Maven install/deploy).
     *
     * @param sourceDir directory to zip (may not exist)
     * @param zipFile   output zip file path
     * @return number of files added to the zip
     * @throws IOException if an I/O error occurs
     */
    static int zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        var count = new AtomicInteger();
        try (OutputStream fos = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            if (Files.isDirectory(sourceDir)) {
                Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file,
                            BasicFileAttributes attrs) throws IOException {
                        String entryName = sourceDir.relativize(file)
                                .toString();
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        count.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return count.get();
    }
}
