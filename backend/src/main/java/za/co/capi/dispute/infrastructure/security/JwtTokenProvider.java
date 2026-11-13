package za.co.capi.dispute.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import za.co.capi.dispute.domain.model.AppUser;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final int MIN_SECRET_BYTES = 32;
    private static final Set<String> KNOWN_UNSAFE_SECRETS = Set.of(
            "CapiBankDisputePortalProductionGradeHmacSha256SecretKey2026Secure!",
            "DemoOnlyJwtSecretKeyNotForProductionUseCapiDisputePortal2026!"
    );

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-seconds:28800}") long expirationSeconds,
            Environment environment) {
        validateSecret(secret, environment);
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    private static void validateSecret(String secret, Environment environment) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret must be configured via APP_JWT_SECRET.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256.");
        }
        boolean nonProdProfile = environment != null
                && environment.acceptsProfiles(Profiles.of("demo", "dev", "test"));
        if (!nonProdProfile && KNOWN_UNSAFE_SECRETS.contains(secret)) {
            throw new IllegalStateException(
                    "Refusing to start with a known demo/example JWT secret outside demo/dev/test profiles.");
        }
    }

    public String generateToken(AppUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("domain", user.getUserDomain().name())
                .claim("tv", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public int extractTokenVersion(Claims claims) {
        Number tv = claims.get("tv", Number.class);
        return tv != null ? tv.intValue() : 0;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}

