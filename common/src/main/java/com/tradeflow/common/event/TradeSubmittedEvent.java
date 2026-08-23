package com.tradeflow.common.event;

import com.tradeflow.common.trade.Side;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka topic {@code trade.submitted} after a trade is persisted (spec §9.2).
 * The shared contract between the producer (trade-ingestion) and the consumer (risk-engine);
 * carries {@code tenantId} so consumers can re-bind the tenant context (DD-09) outside a request.
 */
public record TradeSubmittedEvent(
        UUID eventId,
        String eventType,
        OffsetDateTime occurredAt,
        UUID tenantId,
        UUID tradeId,
        String instrumentSymbol,
        Side side,
        long quantity,
        BigDecimal price) {

    public static final String EVENT_TYPE = "TradeSubmitted";

    /** Build an event for a freshly persisted trade (server-generated eventId + occurredAt). */
    public static TradeSubmittedEvent of(UUID tenantId, UUID tradeId, String instrumentSymbol,
                                         Side side, long quantity, BigDecimal price) {
        return new TradeSubmittedEvent(
                UUID.randomUUID(), EVENT_TYPE, OffsetDateTime.now(),
                tenantId, tradeId, instrumentSymbol, side, quantity, price);
    }
}
