package com.tradeflow.alert;

import com.tradeflow.common.event.RiskBreachedEvent;

/** The payload pushed to a tenant's STOMP topic on a breach. */
public record AlertMessage(
        String type,
        String message,
        String instrumentSymbol,
        long netPosition,
        long positionLimit) {

    public static AlertMessage fromBreach(RiskBreachedEvent event, String message) {
        return new AlertMessage(Alert.TYPE_RISK_BREACH, message,
                event.instrumentSymbol(), event.netPosition(), event.positionLimit());
    }
}
