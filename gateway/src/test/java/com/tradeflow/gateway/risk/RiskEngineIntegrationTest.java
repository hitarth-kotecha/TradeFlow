package com.tradeflow.gateway.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.event.PositionUpdatedEvent;
import com.tradeflow.common.event.RiskBreachedEvent;
import com.tradeflow.common.event.TradeSubmittedEvent;
import com.tradeflow.common.trade.Side;
import com.tradeflow.risk.RiskBreachRepository;
import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.position.PositionStore;
import com.tradeflow.support.IntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end risk-engine pipeline: a {@code trade.submitted} event is consumed, the position is
 * updated atomically in Redis, and a {@code position.updated} event is published. Also verifies
 * idempotency through the whole pipeline (T-IDEM-01). Uses real Kafka + Redis + Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.risk-engine.enabled=true", "app.outbox.relay.enabled=false"})
class RiskEngineIntegrationTest extends IntegrationTest {

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        KAFKA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PositionStore positionStore;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private RiskBreachRepository riskBreachRepository;

    @Test
    void consumesTradeUpdatesPositionAndPublishesPositionUpdated() throws Exception {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL", 10_000);
        UUID tradeId = UUID.randomUUID();

        try (Consumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("position.updated"));

            send(tenantId, tradeId, "CRUDE-OIL", Side.BUY, 100);

            // The consumer applied the delta to Redis...
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(positionStore.position(tenantId, "CRUDE-OIL")).isEqualTo(100L));

            // ...and published position.updated for that trade.
            PositionUpdatedEvent published = awaitPositionUpdated(consumer, tradeId);
            assertThat(published).isNotNull();
            assertThat(published.netPosition()).isEqualTo(100L);
            assertThat(published.tenantId()).isEqualTo(tenantId);
        }
    }

    @Test
    void redeliveredTradeIsAppliedOnce() throws Exception {   // T-IDEM-01 through the pipeline
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "NAT-GAS", 10_000);
        UUID tradeId = UUID.randomUUID();

        // Same event twice = redelivery.
        send(tenantId, tradeId, "NAT-GAS", Side.BUY, 50);
        send(tenantId, tradeId, "NAT-GAS", Side.BUY, 50);

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(positionStore.position(tenantId, "NAT-GAS")).isEqualTo(50L));
        // Stays 50 — the applied-set made the redelivery a no-op.
        Thread.sleep(1_000);
        assertThat(positionStore.position(tenantId, "NAT-GAS")).isEqualTo(50L);
    }

    @Test
    void breachingTradeRecordsBreachAndPublishes() throws Exception {   // T-RISK-01/02
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "OIL", 10_000);
        UUID tradeId = UUID.randomUUID();

        try (Consumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("risk.breached"));

            send(tenantId, tradeId, "OIL", Side.BUY, 11_000);   // exceeds the 10,000 limit

            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(riskBreachRepository.existsByTenantIdAndTradeId(tenantId, tradeId)).isTrue());

            RiskBreachedEvent breach = awaitBreach(consumer, tradeId);
            assertThat(breach).isNotNull();
            assertThat(breach.netPosition()).isEqualTo(11_000L);
            assertThat(breach.positionLimit()).isEqualTo(10_000L);

            // Visible via the breach history API (RISK_MANAGER).
            ResponseEntity<String> breaches = rest.exchange("/api/v1/risk/breaches", HttpMethod.GET,
                    new HttpEntity<>(riskManagerHeaders(tenantId)), String.class);
            assertThat(breaches.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(breaches.getBody()).contains("OIL");
        }
    }

    private RiskBreachedEvent awaitBreach(Consumer<String, String> consumer, UUID tradeId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                RiskBreachedEvent event = objectMapper.readValue(record.value(), RiskBreachedEvent.class);
                if (event.triggeringTradeId().equals(tradeId)) {
                    return event;
                }
            }
        }
        return null;
    }

    private HttpHeaders riskManagerHeaders(UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, Role.RISK_MANAGER));
        return headers;
    }

    private void send(UUID tenantId, UUID tradeId, String symbol, Side side, long quantity) throws Exception {
        TradeSubmittedEvent event = TradeSubmittedEvent.of(
                tenantId, tradeId, symbol, side, quantity, new BigDecimal("72.55"));
        kafkaTemplate.send("trade.submitted", tenantId.toString(), objectMapper.writeValueAsString(event)).get();
    }

    private PositionUpdatedEvent awaitPositionUpdated(Consumer<String, String> consumer, UUID tradeId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
                if (event.triggeringTradeId().equals(tradeId)) {
                    return event;
                }
            }
        }
        return null;
    }

    private Consumer<String, String> newConsumer() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "verify-" + UUID.randomUUID(), "true");
        props.put("auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Risk-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class).getBody().id();
    }

    private void registerInstrument(UUID tenantId, String symbol, long limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, Role.RISK_MANAGER));
        rest.exchange("/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", symbol, "positionLimit", limit), headers), String.class);
    }
}
