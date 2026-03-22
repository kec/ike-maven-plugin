package network.ike.plugin;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CheckBranchSupportTest {

    @Test
    void findComponentFromDirectChild(@TempDir Path tmp) throws Exception {
        // Create a workspace with one component
        Path manifest = tmp.resolve("workspace.yaml");
        java.nio.file.Files.writeString(manifest, """
                schema-version: "1.0"
                components:
                  tinkar-core:
                    type: software
                    branch: main
                    repo: https://example.com/tinkar-core.git
                """);
        Manifest m = ManifestReader.read(manifest);
        WorkspaceGraph graph = new WorkspaceGraph(m);

        File wsRoot = tmp.toFile();
        File componentDir = new File(wsRoot, "tinkar-core");

        String result = CheckBranchMojo.findComponentName(wsRoot, componentDir, graph);
        assertThat(result).isEqualTo("tinkar-core");
    }

    @Test
    void findComponentFromSubmodule(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("workspace.yaml");
        java.nio.file.Files.writeString(manifest, """
                schema-version: "1.0"
                components:
                  ike-pipeline:
                    type: infrastructure
                    branch: main
                    repo: https://example.com/ike-pipeline.git
                """);
        Manifest m = ManifestReader.read(manifest);
        WorkspaceGraph graph = new WorkspaceGraph(m);

        File wsRoot = tmp.toFile();
        // CWD is a submodule: ike-pipeline/ike-parent
        File submoduleDir = new File(wsRoot, "ike-pipeline/ike-parent");
        submoduleDir.mkdirs();

        String result = CheckBranchMojo.findComponentName(wsRoot, submoduleDir, graph);
        assertThat(result).isEqualTo("ike-pipeline");
    }

    @Test
    void returnsNullOutsideWorkspace(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("workspace.yaml");
        java.nio.file.Files.writeString(manifest, """
                schema-version: "1.0"
                components:
                  tinkar-core:
                    type: software
                    branch: main
                    repo: https://example.com/tinkar-core.git
                """);
        Manifest m = ManifestReader.read(manifest);
        WorkspaceGraph graph = new WorkspaceGraph(m);

        File wsRoot = tmp.toFile();
        // CWD is outside the workspace
        File outsideDir = tmp.getParent().toFile();

        String result = CheckBranchMojo.findComponentName(wsRoot, outsideDir, graph);
        assertThat(result).isNull();
    }

    @Test
    void returnsNullForUnknownDirectory(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("workspace.yaml");
        java.nio.file.Files.writeString(manifest, """
                schema-version: "1.0"
                components:
                  tinkar-core:
                    type: software
                    branch: main
                    repo: https://example.com/tinkar-core.git
                """);
        Manifest m = ManifestReader.read(manifest);
        WorkspaceGraph graph = new WorkspaceGraph(m);

        File wsRoot = tmp.toFile();
        File unknownDir = new File(wsRoot, "not-a-component");
        unknownDir.mkdirs();

        String result = CheckBranchMojo.findComponentName(wsRoot, unknownDir, graph);
        assertThat(result).isNull();
    }
}
