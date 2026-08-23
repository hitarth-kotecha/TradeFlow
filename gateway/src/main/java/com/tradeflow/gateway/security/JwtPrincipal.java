package com.tradeflow.gateway.security;

import com.tradeflow.gateway.user.Role;

import java.util.UUID;

/** The identity carried by a verified JWT (spec §11.2 claims: sub, tenantId, role). */
public record JwtPrincipal(UUID userId, UUID tenantId, Role role) {
}
