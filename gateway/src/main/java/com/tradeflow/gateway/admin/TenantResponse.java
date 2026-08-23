package com.tradeflow.gateway.admin;

import com.tradeflow.gateway.tenant.RiskMode;
import com.tradeflow.gateway.tenant.Tenant;
import com.tradeflow.gateway.tenant.TenantStatus;

import java.util.UUID;

public record TenantResponse(UUID id, String name, TenantStatus status, RiskMode riskMode,
                             int rateLimitPerWindow, int rateWindowSeconds) {

    public static TenantResponse from(Tenant t) {
        return new TenantResponse(t.getId(), t.getName(), t.getStatus(), t.getRiskMode(),
                t.getRateLimitPerWindow(), t.getRateWindowSeconds());
    }
}
