package com.tradeflow.gateway.risk;

import com.tradeflow.risk.RiskBreach;

import java.time.OffsetDateTime;

public record BreachResponse(
        String instrumentSymbol,
        long netPosition,
        long positionLimit,
        OffsetDateTime detectedAt) {

    public static BreachResponse from(RiskBreach b) {
        return new BreachResponse(b.getInstrumentSymbol(), b.getNetPosition(),
                b.getPositionLimit(), b.getDetectedAt());
    }
}
