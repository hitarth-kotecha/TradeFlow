package com.tradeflow.gateway.tenant;

import com.tradeflow.gateway.web.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant onboarding (FR-TEN-01). */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant onboard(String name, RiskMode riskMode) {
        if (tenantRepository.existsByName(name)) {
            throw new ConflictException("Tenant name already in use: " + name);
        }
        Tenant tenant = Tenant.onboard(name, riskMode == null ? RiskMode.MONITOR : riskMode);
        return tenantRepository.save(tenant);
    }
}
