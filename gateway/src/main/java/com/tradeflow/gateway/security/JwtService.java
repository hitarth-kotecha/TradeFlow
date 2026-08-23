package com.tradeflow.gateway.security;

import com.tradeflow.gateway.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 JWTs (Concept 1). Signing and verification use the same secret key
 * (symmetric), so only this application can mint or validate a token.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(JwtProperties props) {
        // hmacShaKeyFor enforces a >= 256-bit key for HS256 (fails fast on a too-short secret).
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = props.expirySeconds();
    }

    /** Mint a signed token carrying userId (sub), tenantId, role, and expiry (FR-TEN-04). */
    public String issue(UUID userId, UUID tenantId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)   // 256-bit key -> HS256 selected automatically
                .compact();
    }

    /**
     * Verify signature + expiry (with 30s clock-skew tolerance, §11.3) and extract the identity.
     * Throws {@link io.jsonwebtoken.JwtException} on any invalid/expired/tampered token.
     */
    public JwtPrincipal verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new JwtPrincipal(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get("tenantId", String.class)),
                Role.valueOf(claims.get("role", String.class)));
    }

    public long expirySeconds() {
        return expirySeconds;
    }
}
