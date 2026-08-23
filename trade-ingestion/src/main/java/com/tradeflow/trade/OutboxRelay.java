package com.tradeflow.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes the transactional outbox to Kafka (DD-03, NFR-CONC-02). A scheduled poller reads the
 * oldest PENDING rows and, for each, sends the stored JSON to its topic keyed by {@code tenantId}
 * (Concept 2 — per-tenant ordering), then marks it DISPATCHED. A crash between publish and mark
 * simply re-publishes next cycle → at-least-once (consumers dedupe).
 *
 * <p>With {@code spring.threads.virtual.enabled=true}, the scheduled task runs on a virtual thread,
 * so its blocking send/ack waits scale cheaply. Disabled via property in tests that don't need it.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void publishPending() {
        List<OutboxEvent> batch =
                outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, Limit.of(BATCH_SIZE));
        for (OutboxEvent event : batch) {
            try {
                publish(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;   // stop this cycle; resume next poll
            } catch (Exception e) {
                // Leave the row PENDING; it will be retried. (A DLT after N retries is future work, §9.4.)
                log.warn("Outbox publish failed for {}; will retry", event.getId(), e);
            }
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        // Every event schema carries tenantId (§9.2); use it as the partition key for ordering.
        String partitionKey = objectMapper.readTree(event.getPayload()).path("tenantId").asText();
        kafkaTemplate.send(event.getTopic(), partitionKey, event.getPayload()).get();   // block for the ack
        event.markDispatched();
        outboxRepository.save(event);
    }
}
