package com.tradeflow.trade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.event.TradeSubmittedEvent;
import com.tradeflow.common.trade.Side;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ingests a validated trade. The trade row and its outbox event are written in ONE transaction
 * (the transactional outbox, DD-03), so they commit together or not at all — no dual-write hole.
 * A relay later publishes the outbox row to Kafka (at-least-once).
 */
@Service
public class TradeIngestionService {

    static final String AGGREGATE_TYPE = "TRADE";
    static final String TOPIC_TRADE_SUBMITTED = "trade.submitted";

    private final TradeRepository tradeRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TradeIngestionService(TradeRepository tradeRepository, OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper) {
        this.tradeRepository = tradeRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Trade ingest(UUID tenantId, UUID userId, String instrumentSymbol,
                        Side side, long quantity, BigDecimal price, String idempotencyKey) {
        // Retry of a previously accepted trade → return the original, create nothing (FR-TRADE-06).
        if (idempotencyKey != null) {
            var existing = tradeRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Trade trade = Trade.accepted(tenantId, userId, instrumentSymbol, side, quantity, price, idempotencyKey);
        tradeRepository.save(trade);

        // Same transaction as the trade: either both land or neither does.
        TradeSubmittedEvent event = TradeSubmittedEvent.of(
                tenantId, trade.getId(), instrumentSymbol, side, quantity, price);
        OutboxEvent outbox = OutboxEvent.pending(
                AGGREGATE_TYPE, trade.getId(), TOPIC_TRADE_SUBMITTED, serialize(event));
        outboxRepository.save(outbox);

        return trade;
    }

    private String serialize(TradeSubmittedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + event.eventType() + " event", e);
        }
    }
}
