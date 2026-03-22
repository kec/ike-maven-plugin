package network.ike.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions extracted from {@link DeploySiteMojo}:
 * URL conversion from internal SCP to public HTTP.
 */
class DeploySiteSupportTest {

    // ── toPublicSiteUrl ─────────────────────────────────────────────

    @Test
    void toPublicSiteUrl_releaseUrl() {
        String scp = "scpexe://proxy/srv/ike-site/ike-pipeline/release";
        assertThat(DeploySiteMojo.toPublicSiteUrl(scp))
                .isEqualTo("http://ike.komet.sh/ike-pipeline/release");
    }

    @Test
    void toPublicSiteUrl_snapshotWithBranch() {
        String scp = "scpexe://proxy/srv/ike-site/ike-docs/snapshot/main";
        assertThat(DeploySiteMojo.toPublicSiteUrl(scp))
                .isEqualTo("http://ike.komet.sh/ike-docs/snapshot/main");
    }

    @Test
    void toPublicSiteUrl_checkpointWithVersion() {
        String scp = "scpexe://proxy/srv/ike-site/ike-pipeline/checkpoint/20-checkpoint.20260315.1";
        assertThat(DeploySiteMojo.toPublicSiteUrl(scp))
                .isEqualTo("http://ike.komet.sh/ike-pipeline/checkpoint/20-checkpoint.20260315.1");
    }

    @Test
    void toPublicSiteUrl_featureBranch() {
        String scp = "scpexe://proxy/srv/ike-site/ike-pipeline/snapshot/feature/my-work";
        assertThat(DeploySiteMojo.toPublicSiteUrl(scp))
                .isEqualTo("http://ike.komet.sh/ike-pipeline/snapshot/feature/my-work");
    }

    @Test
    void toPublicSiteUrl_noPrefix_unchanged() {
        String url = "http://example.com/some/path";
        assertThat(DeploySiteMojo.toPublicSiteUrl(url))
                .isEqualTo("http://example.com/some/path");
    }
}
