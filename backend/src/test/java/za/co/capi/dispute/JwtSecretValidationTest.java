package za.co.capi.dispute;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import za.co.capi.dispute.infrastructure.security.JwtTokenProvider;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidationTest {

    private static final String STRONG_PROD_SECRET =
            "9f8e7d6c5b4a392817263544536271809a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d";

    @Test
    void rejectsKnownExampleSecretOutsideNonProdProfiles() {
        MockEnvironment prodEnv = new MockEnvironment();

        assertThatThrownBy(() -> new JwtTokenProvider(
                "CapiBankDisputePortalProductionGradeHmacSha256SecretKey2026Secure!",
                3600,
                prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known demo/example JWT secret");

        assertThatThrownBy(() -> new JwtTokenProvider(
                "DemoOnlyJwtSecretKeyNotForProductionUseCapiDisputePortal2026!",
                3600,
                prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known demo/example JWT secret");
    }

    @Test
    void rejectsBlankOrShortJwtSecret() {
        MockEnvironment prodEnv = new MockEnvironment();

        assertThatThrownBy(() -> new JwtTokenProvider("   ", 3600, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured");

        assertThatThrownBy(() -> new JwtTokenProvider("too-short-secret", 3600, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void acceptsStrongSecretInDefaultProfileAndDemoSecretInDemoProfile() {
        MockEnvironment prodEnv = new MockEnvironment();
        assertThatNoException().isThrownBy(() ->
                new JwtTokenProvider(STRONG_PROD_SECRET, 3600, prodEnv));

        MockEnvironment demoEnv = new MockEnvironment();
        demoEnv.setActiveProfiles("demo");
        assertThatNoException().isThrownBy(() ->
                new JwtTokenProvider(
                        "DemoOnlyJwtSecretKeyNotForProductionUseCapiDisputePortal2026!",
                        3600,
                        demoEnv));
    }
}

