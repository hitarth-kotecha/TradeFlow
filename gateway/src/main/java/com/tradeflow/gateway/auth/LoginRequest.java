package com.tradeflow.gateway.auth;

import jakarta.validation.constraints.NotBlank;

/** Tenant-aware login payload (spec §10.1). */
public record LoginRequest(
        @NotBlank String tenantName,
        @NotBlank String email,
        @NotBlank String password) {
}
