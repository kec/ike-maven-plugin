package network.ike.plugin;

import org.junit.jupiter.api.Test;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies AdocStudioMojo resource bundling and path resolution.
 *
 * <p>The core generation logic is in the Swift script and requires
 * macOS — those paths are exercised by manual integration testing.
 * This test covers the Java-side contract: the script resource is
 * present, and tilde expansion works.
 */
class AdocStudioMojoTest {

    private static final String SWIFT_RESOURCE =
            "/adocstudio/bootstrap-adocprojects.swift";

    @Test
    void swiftScriptResourceIsOnClasspath() {
        try (InputStream is = getClass().getResourceAsStream(SWIFT_RESOURCE)) {
            assertThat(is)
                    .as("Swift script must be bundled in plugin JAR")
                    .isNotNull();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void swiftScriptContainsExpectedEntryPoint() throws Exception {
        try (InputStream is = getClass().getResourceAsStream(SWIFT_RESOURCE)) {
            String content = new String(is.readAllBytes());
            assertThat(content)
                    .contains("discoverAssemblies")
                    .contains("createBookmark")
                    .contains("generateAdocProject");
        }
    }

    @Test
    void resolveHomeExpandsTilde() {
        AdocStudioMojo mojo = new AdocStudioMojo();
        String home = System.getProperty("user.home");

        assertThat(invokeResolveHome(mojo, "~/Documents/test"))
                .isEqualTo(home + "/Documents/test");

        assertThat(invokeResolveHome(mojo, "~"))
                .isEqualTo(home);
    }

    @Test
    void resolveHomePassesThroughAbsolutePath() {
        AdocStudioMojo mojo = new AdocStudioMojo();

        assertThat(invokeResolveHome(mojo, "/opt/adocstudio"))
                .isEqualTo("/opt/adocstudio");
    }

    @Test
    void resolveHomePassesThroughRelativePath() {
        AdocStudioMojo mojo = new AdocStudioMojo();

        assertThat(invokeResolveHome(mojo, "relative/path"))
                .isEqualTo("relative/path");
    }

    /**
     * Reflective call to the private {@code resolveHome} method.
     * Acceptable here because the method is a pure function and
     * extracting a Support class for three lines is not warranted.
     */
    private String invokeResolveHome(AdocStudioMojo mojo, String path) {
        try {
            var method = AdocStudioMojo.class
                    .getDeclaredMethod("resolveHome", String.class);
            method.setAccessible(true);
            return (String) method.invoke(mojo, path);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
