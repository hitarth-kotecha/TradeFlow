package com.tradeflow.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.context.TenantContext;
import com.tradeflow.common.event.PositionUpdatedEvent;
import com.tradeflow.common.event.RiskBreachedEvent;
import com.tradeflow.common.event.TradeSubmittedEvent;
import com.tradeflow.position.PositionStore;
import com.tradeflow.position.PositionUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes {@code trade.submitted} (§13.1). Per record: re-bind the tenant context from the payload
 * (DD-09, since consumer threads are outside any request), atomically apply the trade to the position
 * and evaluate the limit (DD-06), then publish {@code position.updated}. Idempotent: a redelivered
 * trade is a no-op (the Lua applied-set), so at-least-once delivery is safe (NFR-REL-02).
 *
 * <p>Per-partition, single-threaded consumption preserves per-tenant ordering — we do NOT fan out
 * the per-trade update (§12.4).
 */
@Component
@ConditionalOnProperty(name = "app.risk-engine.enabled", havingValue = "true", matchIfMissing = true)
public class RiskEngineConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineConsumer.class);
    static final String TOPIC_POSITION_UPDATED = "position.updated";
    static final String TOPIC_RISK_BREACHED = "risk.breached";

    private final PositionStore positionStore;
    private final LimitResolver limitResolver;
    private final BreachRecorder breachRecorder;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RiskEngineConsumer(PositionStore positionStore, LimitResolver limitResolver,
                              BreachRecorder breachRecorder, KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper) {
        this.positionStore = positionStore;
        this.limitResolver = limitResolver;
        this.breachRecorder = breachRecorder;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "trade.submitted", groupId = "risk-engine")
    public void onTradeSubmitted(String payload) {
        TradeSubmittedEvent event = deserialize(payload);
        // Re-bind tenant context so downstream code (and forked subtasks) see the right tenant.
        ScopedValue.where(TenantContext.TENANT_ID, event.tenantId())
                .where(TenantContext.TRACE_ID, UUID.randomUUID().toString())
                .run(() -> process(event));
    }

    private void process(TradeSubmittedEvent event) {
        long delta = event.side().sign() * event.quantity();
        long limit = limitResolver.limitFor(event.tenantId(), event.instrumentSymbol());

        PositionUpdate update = positionStore.applyAndEvaluate(
                event.tenantId(), event.instrumentSymbol(), event.tradeId(), delta, limit);

        if (update.alreadyApplied()) {
            log.debug("Trade {} already applied; skipping", event.tradeId());
            return;
        }

        publish(TOPIC_POSITION_UPDATED, event.tenantId(),
                PositionUpdatedEvent.of(event.tenantId(), event.instrumentSymbol(),
                        update.newPosition(), event.tradeId()));

        if (update.breached()) {
            boolean recorded = breachRecorder.record(event.tenantId(), event.tradeId(),
                    event.instrumentSymbol(), update.newPosition(), limit);
            if (recorded) {
                publish(TOPIC_RISK_BREACHED, event.tenantId(),
                        RiskBreachedEvent.of(event.tenantId(), event.instrumentSymbol(),
                                update.newPosition(), limit, event.tradeId()));
            }
        }
    }

    private void publish(String topic, UUID tenantId, Object event) {
        kafkaTemplate.send(topic, tenantId.toString(), serialize(event));
    }

    private TradeSubmittedEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, TradeSubmittedEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed trade.submitted payload", e);
        }
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }
}
