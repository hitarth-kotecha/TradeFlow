package com.tradeflow.risk;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persists breaches idempotently (FR-RISK-03). Returns whether a NEW breach was recorded. */
@Service
public class BreachRecorder {

    private final RiskBreachRepository riskBreachRepository;

    public BreachRecorder(RiskBreachRepository riskBreachRepository) {
        this.riskBreachRepository = riskBreachRepository;
    }

    @Transactional
    public boolean record(UUID tenantId, UUID tradeId, String instrumentSymbol, long netPosition, long limit) {
        if (riskBreachRepository.existsByTenantIdAndTradeId(tenantId, tradeId)) {
            return false;   // already recorded (belt-and-suspenders with the applied-set)
        }
        riskBreachRepository.save(RiskBreach.of(tenantId, tradeId, instrumentSymbol, netPosition, limit));
        return true;
    }
}
