package com.tradeflow.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.context.TenantContext;
import com.tradeflow.common.event.RiskBreachedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes {@code risk.breached} (FR-ALERT-01) and persists an alert for the affected tenant.
 * The live WebSocket push is added in the next slice.
 */
@Component
@ConditionalOnProperty(name = "app.alert-service.enabled", havingValue = "true", matchIfMissing = true)
public class AlertConsumer {

    private final AlertService alertService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public AlertConsumer(AlertService alertService, SimpMessagingTemplate messagingTemplate,
                         ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "risk.breached", groupId = "alert-service")
    public void onRiskBreached(String payload) {
        RiskBreachedEvent event = deserialize(payload);
        ScopedValue.where(TenantContext.TENANT_ID, event.tenantId())
                .where(TenantContext.TRACE_ID, UUID.randomUUID().toString())
                .run(() -> handle(event, payload));
    }

    private void handle(RiskBreachedEvent event, String payload) {
        Alert alert = alertService.recordBreachAlert(event, payload);   // durable (FR-ALERT-04)
        // Live push to the affected tenant's topic only (FR-ALERT-01/03).
        messagingTemplate.convertAndSend(
                "/topic/tenant." + event.tenantId() + ".alerts",
                AlertMessage.fromBreach(event, alert.getMessage()));
    }

    private RiskBreachedEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, RiskBreachedEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed risk.breached payload", e);
        }
    }
}
