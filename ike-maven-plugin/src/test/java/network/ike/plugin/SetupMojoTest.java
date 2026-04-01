package network.ike.plugin;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SetupMojo} hook resources.
 *
 * <p>Verifies that hook scripts are present on the classpath and
 * contain the expected content structure.
 */
class SetupMojoTest {

    @Test
    void preCommitHook_existsOnClasspath() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/pre-commit")) {
            assertThat(is).isNotNull();
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("#!/usr/bin/env bash");
            assertThat(content).contains("IKE VCS Bridge");
            assertThat(content).contains("IKE_VCS_CONTEXT");
            assertThat(content).contains("IKE_VCS_OVERRIDE");
            assertThat(content).contains(".ike/vcs-state");
            assertThat(content).contains("ike:sync");
        }
    }

    @Test
    void postCommitHook_existsOnClasspath() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/post-commit")) {
            assertThat(is).isNotNull();
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("#!/usr/bin/env bash");
            assertThat(content).contains("IKE VCS Bridge");
            assertThat(content).contains(".ike/vcs-state");
            assertThat(content).contains("action=commit");
            assertThat(content).contains("git push origin");
        }
    }

    @Test
    void prePushHook_existsOnClasspath() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/pre-push")) {
            assertThat(is).isNotNull();
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("#!/usr/bin/env bash");
            assertThat(content).contains("IKE VCS Bridge");
            assertThat(content).contains("IKE_VCS_CONTEXT");
            assertThat(content).contains("0000000000000000000000000000000000000000");
            assertThat(content).contains("action=push");
        }
    }

    @Test
    void preCommitHook_usesPropertiesFormat() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/pre-commit")) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Verify grep/cut parsing for properties format
            assertThat(content).contains("grep '^sha='");
            assertThat(content).contains("cut -d= -f2");
        }
    }

    @Test
    void postCommitHook_usesHostnameSubstitution() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/post-commit")) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Cross-platform hostname: ${HOSTNAME%%.*}
            assertThat(content).contains("${HOSTNAME%%.*}");
        }
    }

    @Test
    void preCommitHook_shortSha8() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/pre-commit")) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("--short=8");
        }
    }

    @Test
    void postCommitHook_shortSha8() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hooks/post-commit")) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("--short=8");
        }
    }
}
