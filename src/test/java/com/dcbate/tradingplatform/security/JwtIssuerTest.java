package com.dcbate.tradingplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dcbate.tradingplatform.exception.InvalidRefreshTokenException;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtIssuerTest {

    private final JwtIssuer jwtIssuer = new JwtIssuer("local-dev-secret-change-me-please-32bytes", "trading-platform");

    @Test
    void accessTokenCarriesTheClientAsSubjectAndAClientRole() throws Exception {
        String token = jwtIssuer.issueAccessToken("client-1", Instant.now().plusSeconds(60));

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("client-1");
        assertThat(parsed.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("CLIENT");
    }

    @Test
    void refreshTokenRoundTripsThroughVerification() {
        UUID tokenId = UUID.randomUUID();
        String token = jwtIssuer.issueRefreshToken("client-1", tokenId, Instant.now().plusSeconds(60));

        assertThat(jwtIssuer.verifyRefreshToken(token)).isEqualTo(tokenId);
        assertThat(jwtIssuer.subjectOf(token)).isEqualTo("client-1");
    }

    @Test
    void verifyRefreshTokenRejectsAnExpiredToken() {
        String token = jwtIssuer.issueRefreshToken("client-1", UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.SECONDS));

        assertThatThrownBy(() -> jwtIssuer.verifyRefreshToken(token))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void verifyRefreshTokenRejectsAnAccessTokenPresentedAsARefreshToken() {
        String accessToken = jwtIssuer.issueAccessToken("client-1", Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> jwtIssuer.verifyRefreshToken(accessToken))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void verifyRefreshTokenRejectsATokenSignedWithADifferentSecret() {
        JwtIssuer otherIssuer = new JwtIssuer("a-completely-different-secret-that-is-also-32-bytes", "trading-platform");
        String token = otherIssuer.issueRefreshToken("client-1", UUID.randomUUID(), Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> jwtIssuer.verifyRefreshToken(token))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refusesToStartWithTheInsecureDefaultSecretOutsideDevOrTest() {
        MockEnvironment prodLikeEnvironment = new MockEnvironment();
        prodLikeEnvironment.setActiveProfiles("k8s");

        assertThatThrownBy(() -> new JwtIssuer("local-dev-secret-change-me-please-32bytes", "trading-platform", prodLikeEnvironment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void allowsTheInsecureDefaultSecretUnderTheDevProfile() {
        MockEnvironment devEnvironment = new MockEnvironment();
        devEnvironment.setActiveProfiles("docker", "dev");

        assertThatCode(() -> new JwtIssuer("local-dev-secret-change-me-please-32bytes", "trading-platform", devEnvironment))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAnyRealSecretRegardlessOfProfile() {
        MockEnvironment prodLikeEnvironment = new MockEnvironment();
        prodLikeEnvironment.setActiveProfiles("k8s");

        assertThatCode(() -> new JwtIssuer("a-genuinely-random-production-secret-32b", "trading-platform", prodLikeEnvironment))
                .doesNotThrowAnyException();
    }
}
