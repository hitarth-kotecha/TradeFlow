package com.tradeflow.gateway.tenant;

/** Tenant lifecycle state (mirrors the CHECK constraint on tenants.status). */
public enum TenantStatus {
    ACTIVE,
    INACTIVE
}
