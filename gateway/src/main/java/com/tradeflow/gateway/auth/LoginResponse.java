package com.tradeflow.gateway.auth;

import com.tradeflow.gateway.user.Role;

import java.util.UUID;

/** Login result (spec §10.1). */
public record LoginResponse(String token, long expiresIn, Role role, UUID tenantId) {
}
