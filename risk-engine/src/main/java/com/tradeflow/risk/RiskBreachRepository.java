package com.tradeflow.risk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RiskBreachRepository extends JpaRepository<RiskBreach, UUID> {

    boolean existsByTenantIdAndTradeId(UUID tenantId, UUID tradeId);

    Page<RiskBreach> findByTenantId(UUID tenantId, Pageable pageable);
}
