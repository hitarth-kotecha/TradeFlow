package com.tradeflow.common.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to {@code risk.breached} when a trade pushes a net position past its limit (spec §9.2).
 * Consumed by alert-service to push a real-time alert to the affected tenant.
 */
public record RiskBreachedEvent(
        UUID eventId,
        String eventType,
        OffsetDateTime occurredAt,
        UUID tenantId,
        String instrumentSymbol,
        long netPosition,
        long positionLimit,
        UUID triggeringTradeId) {

    public static final String EVENT_TYPE = "RiskBreached";

    public static RiskBreachedEvent of(UUID tenantId, String instrumentSymbol,
                                       long netPosition, long positionLimit, UUID triggeringTradeId) {
        return new RiskBreachedEvent(UUID.randomUUID(), EVENT_TYPE, OffsetDateTime.now(),
                tenantId, instrumentSymbol, netPosition, positionLimit, triggeringTradeId);
    }
}
