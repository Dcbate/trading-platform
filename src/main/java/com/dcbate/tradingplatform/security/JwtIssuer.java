package com.dcbate.tradingplatform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.dcbate.tradingplatform.exception.InvalidRefreshTokenException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Mints the HS256 JWTs {@code SecurityConfig}'s resource-server filter chain validates — the
 * issuing half that was genuinely missing before ({@code NimbusJwtDecoder} only ever validated).
 * Signs with the same {@code jwt.secret} the decoder verifies against, so a token minted here
 * passes the existing filter chain unchanged; no new trust relationship to wire up.
 */
@Component
public class JwtIssuer {

    /** application.yml's {@code jwt.secret} fallback — fine for local dev/tests, never for a real deployment. */
    private static final String INSECURE_DEFAULT_SECRET = "local-dev-secret-change-me-please-32bytes";

    private final byte[] secretBytes;
    private final String issuer;

    @Autowired
    public JwtIssuer(@Value("${jwt.secret}") String secret, @Value("${jwt.issuer}") String issuer, Environment environment) {
        this(rejectInsecureDefaultOutsideDevOrTest(secret, environment), issuer);
    }

    JwtIssuer(String secret, String issuer) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
    }

    // Fails app startup outright rather than silently signing real tokens with a secret that's
    // sitting in plaintext in application.yml — a deployment that forgets to set JWT_SECRET would
    // otherwise run "successfully" with forgeable auth.
    private static String rejectInsecureDefaultOutsideDevOrTest(String secret, Environment environment) {
        if (INSECURE_DEFAULT_SECRET.equals(secret) && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException(
                    "jwt.secret is still the insecure default outside the dev/test profiles — set the JWT_SECRET environment variable before starting.");
        }
        return secret;
    }

    /** An access token: {@code sub}=clientId, {@code roles} claim the resource server reads authorities from. */
    public String issueAccessToken(String clientId, Instant expiresAt) {
        return sign(new JWTClaimsSet.Builder()
                .subject(clientId)
                .issuer(issuer)
                .claim("roles", List.of("CLIENT"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiresAt)));
    }

    /** A refresh token: no roles (never accepted by the resource server), carries {@code jti} so the issuing row can be looked up and revoked on use. */
    public String issueRefreshToken(String clientId, UUID tokenId, Instant expiresAt) {
        return sign(new JWTClaimsSet.Builder()
                .subject(clientId)
                .issuer(issuer)
                .jwtID(tokenId.toString())
                .claim("typ", "refresh")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiresAt)));
    }

    /** Verifies signature and expiry, and that this is genuinely a refresh token (not an access token replayed here). Returns the {@code jti}. */
    public UUID verifyRefreshToken(String token) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);
            if (!signedJwt.verify(new MACVerifier(secretBytes))) {
                throw new InvalidRefreshTokenException("bad signature");
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            if (!"refresh".equals(claims.getStringClaim("typ"))) {
                throw new InvalidRefreshTokenException("not a refresh token");
            }
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                throw new InvalidRefreshTokenException("expired");
            }
            return UUID.fromString(claims.getJWTID());
        } catch (InvalidRefreshTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidRefreshTokenException("malformed token");
        }
    }

    /** The clientId (subject) carried by an already-verified refresh token. */
    public String subjectOf(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getSubject();
        } catch (Exception e) {
            throw new InvalidRefreshTokenException("malformed token");
        }
    }

    private String sign(JWTClaimsSet.Builder claims) {
        try {
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            signedJwt.sign(new MACSigner(secretBytes));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }
}
