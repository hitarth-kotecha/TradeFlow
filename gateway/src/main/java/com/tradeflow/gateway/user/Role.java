package com.tradeflow.gateway.user;

/** User role within a tenant (mirrors the CHECK constraint on users.role). Drives @PreAuthorize checks. */
public enum Role {
    TRADER,
    RISK_MANAGER,
    ADMIN
}
