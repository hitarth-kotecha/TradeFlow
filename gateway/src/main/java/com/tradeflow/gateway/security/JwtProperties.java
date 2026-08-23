package com.tradeflow.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.jwt.*}. The secret is the shared HS256 key (from env in real deploys);
 * expirySeconds controls token lifetime (short-lived, since stateless JWTs are hard to revoke).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long expirySeconds) {
}
