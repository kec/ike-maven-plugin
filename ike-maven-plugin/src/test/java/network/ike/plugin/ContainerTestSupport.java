package network.ike.plugin;

import org.junit.jupiter.api.Tag;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

/**
 * Base class for integration tests that require Docker containers.
 * Tagged with "container" so they are excluded from the default build
 * and run only with {@code mvn verify -Dgroups=container}.
 */
@Tag("container")
abstract class ContainerTestSupport {

    /**
     * Check whether Docker is reachable. Tests are skipped if not.
     *
     * @return true if Docker daemon responds
     */
    static boolean isDockerAvailable() {
        try {
            Process proc = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate an ephemeral RSA keypair for SSH authentication in tests.
     *
     * @return RSA keypair (2048-bit)
     */
    static KeyPair generateSshKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA not available", e);
        }
    }
}
