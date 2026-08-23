package com.tradeflow.gateway.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.alert.AlertMessage;
import com.tradeflow.common.event.RiskBreachedEvent;
import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-ALERT-01: a breach alert is pushed over WebSocket only to the owning tenant's subscriber.
 * A real STOMP client connects (JWT on CONNECT), subscribes to its tenant topic, and receives the
 * alert; a second tenant's subscriber receives nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.alert-service.enabled=true",
                "app.risk-engine.enabled=false",
                "app.outbox.relay.enabled=false"
        })
class AlertDeliveryTest extends IntegrationTest {

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    static {
        KAFKA.start();
    }

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void breachAlertReachesOnlyTheOwningTenant() throws Exception {
        UUID tenantA = onboardTenant();
        UUID tenantB = onboardTenant();

        BlockingQueue<AlertMessage> tenantAInbox = subscribe(tenantA);
        BlockingQueue<AlertMessage> tenantBInbox = subscribe(tenantB);
        Thread.sleep(500);   // let the subscriptions register

        // A breach for tenant A.
        RiskBreachedEvent event = RiskBreachedEvent.of(tenantA, "OIL", 11_000, 10_000, UUID.randomUUID());
        kafkaTemplate.send("risk.breached", tenantA.toString(), objectMapper.writeValueAsString(event)).get();

        AlertMessage delivered = tenantAInbox.poll(20, TimeUnit.SECONDS);
        assertThat(delivered).as("owning tenant receives the alert").isNotNull();
        assertThat(delivered.instrumentSymbol()).isEqualTo("OIL");
        assertThat(delivered.netPosition()).isEqualTo(11_000L);

        assertThat(tenantBInbox.poll(2, TimeUnit.SECONDS))
                .as("other tenant receives nothing").isNull();

        // The alert is also persisted and fetchable via the history API (FR-ALERT-04).
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantA, Role.RISK_MANAGER));
        var history = rest.exchange("/api/v1/alerts", HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(history.getBody()).contains("RISK_BREACH").contains("OIL");
    }

    private BlockingQueue<AlertMessage> subscribe(UUID tenantId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + jwtService.issue(UUID.randomUUID(), tenantId, Role.TRADER));

        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                new org.springframework.web.socket.WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);

        BlockingQueue<AlertMessage> inbox = new LinkedBlockingQueue<>();
        session.subscribe("/topic/tenant." + tenantId + ".alerts", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return AlertMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((AlertMessage) payload);
            }
        });
        return inbox;
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Alert-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class).getBody().id();
    }
}
