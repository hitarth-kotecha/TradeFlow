package com.tradeflow.common.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to {@code position.updated} after the risk engine applies a trade to a net position
 * (spec §9.2). Consumed by reporting/audit.
 */
public record PositionUpdatedEvent(
        UUID eventId,
        String eventType,
        OffsetDateTime occurredAt,
        UUID tenantId,
        String instrumentSymbol,
        long netPosition,
        UUID triggeringTradeId) {

    public static final String EVENT_TYPE = "PositionUpdated";

    public static PositionUpdatedEvent of(UUID tenantId, String instrumentSymbol,
                                          long netPosition, UUID triggeringTradeId) {
        return new PositionUpdatedEvent(UUID.randomUUID(), EVENT_TYPE, OffsetDateTime.now(),
                tenantId, instrumentSymbol, netPosition, triggeringTradeId);
    }
}
