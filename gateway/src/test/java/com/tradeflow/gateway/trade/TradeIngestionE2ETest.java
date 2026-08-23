package com.tradeflow.gateway.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.event.TradeSubmittedEvent;
import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.admin.UserResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (T-TRADE flow, FR-TRADE-04): submit a trade over HTTP and assert the outbox relay
 * publishes a TradeSubmittedEvent to {@code trade.submitted}, keyed by tenantId. The relay is
 * enabled for this test (overriding the base), and a real Kafka container is wired via
 * {@code @ServiceConnection}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.outbox.relay.enabled=true",
                "app.risk-engine.enabled=false"   // this test only covers ingestion → relay → Kafka
        })
class TradeIngestionE2ETest extends IntegrationTest {

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    static {
        KAFKA.start();
    }

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void submittedTradeIsPublishedToKafka() throws Exception {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL");
        HttpHeaders trader = traderHeaders(tenantId);

        try (Consumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("trade.submitted"));

            var response = rest.exchange("/api/v1/trades", HttpMethod.POST,
                    new HttpEntity<>(tradeBody(), trader), TradeResponse.class);
            UUID tradeId = response.getBody().tradeId();

            // The shared Postgres may hold other tests' outbox rows, so the relay can emit several
            // events — poll until we find OURS (by tradeId).
            ConsumerRecord<String, String> record = findRecordForTrade(consumer, tradeId);

            assertThat(record).as("event for the submitted trade should reach trade.submitted").isNotNull();
            assertThat(record.key()).isEqualTo(tenantId.toString());   // keyed by tenantId (Concept 2)
            TradeSubmittedEvent event = objectMapper.readValue(record.value(), TradeSubmittedEvent.class);
            assertThat(event.tenantId()).isEqualTo(tenantId);
            assertThat(event.eventType()).isEqualTo("TradeSubmitted");
        }
    }

    private ConsumerRecord<String, String> findRecordForTrade(Consumer<String, String> consumer, UUID tradeId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                if (objectMapper.readValue(record.value(), TradeSubmittedEvent.class).tradeId().equals(tradeId)) {
                    return record;
                }
            }
        }
        return null;
    }

    private Consumer<String, String> newConsumer() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "e2e-" + UUID.randomUUID(), "true");
        props.put("auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }

    private Map<String, Object> tradeBody() {
        return Map.of("instrumentSymbol", "CRUDE-OIL", "side", "BUY", "quantity", 100, "price", 72.55);
    }

    private UUID onboardTenant() {
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "E2E-" + UUID.randomUUID(), "riskMode", "MONITOR"), adminHeaders()),
                TenantResponse.class).getBody().id();
    }

    private void registerInstrument(UUID tenantId, String symbol) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, Role.RISK_MANAGER));
        rest.exchange("/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", symbol, "positionLimit", 10_000), headers), String.class);
    }

    private HttpHeaders traderHeaders(UUID tenantId) {
        UUID userId = rest.exchange("/admin/tenants/" + tenantId + "/users", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", UUID.randomUUID() + "@t.com", "password", "secret123", "role", "TRADER"),
                        adminHeaders()),
                UserResponse.class).getBody().id();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(userId, tenantId, Role.TRADER));
        headers.set("Idempotency-Key", "idem-" + UUID.randomUUID());
        return headers;
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return headers;
    }
}
