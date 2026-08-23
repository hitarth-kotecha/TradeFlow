package com.tradeflow.gateway.admin;

import com.tradeflow.gateway.tenant.RiskMode;
import jakarta.validation.constraints.NotBlank;

/** Tenant onboarding payload (spec §10.2). riskMode is optional and defaults to MONITOR. */
public record CreateTenantRequest(@NotBlank String name, RiskMode riskMode) {
}
