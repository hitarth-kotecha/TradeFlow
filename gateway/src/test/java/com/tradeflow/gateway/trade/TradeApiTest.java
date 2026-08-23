package com.tradeflow.gateway.trade;

import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.admin.UserResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import com.tradeflow.trade.OutboxRepository;
import com.tradeflow.trade.OutboxStatus;
import com.tradeflow.trade.TradeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** POST /trades: persistence + outbox write (Concept 1), validation, and idempotency. */
class TradeApiTest extends IntegrationTest {

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void submitsTradePersistsItAndWritesAnOutboxEvent() {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL");

        ResponseEntity<TradeResponse> response = rest.exchange(
                "/api/v1/trades", HttpMethod.POST,
                new HttpEntity<>(tradeBody(), traderHeaders(tenantId, "idem-" + UUID.randomUUID())),
                TradeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(TradeStatus.ACCEPTED);
        UUID tradeId = response.getBody().tradeId();

        // The outbox row was written in the same transaction (DD-03), still PENDING (relay comes next slice).
        assertThat(outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, Limit.of(100)))
                .anyMatch(o -> o.getAggregateId().equals(tradeId) && o.getTopic().equals("trade.submitted"));
    }

    @Test
    void rejectsInvalidQuantity() {   // V1
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/trades", HttpMethod.POST,
                new HttpEntity<>(Map.of("instrumentSymbol", "CRUDE-OIL", "side", "BUY", "quantity", 0, "price", 72.55),
                        traderHeaders(tenantId, null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownInstrument() {   // V4
        UUID tenantId = onboardTenant();

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/trades", HttpMethod.POST,
                new HttpEntity<>(tradeBody(), traderHeaders(tenantId, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void idempotentReplayReturnsTheSameTrade() {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL");
        HttpHeaders headers = traderHeaders(tenantId, "idem-" + UUID.randomUUID());

        UUID first = rest.exchange("/api/v1/trades", HttpMethod.POST,
                new HttpEntity<>(tradeBody(), headers), TradeResponse.class).getBody().tradeId();
        UUID second = rest.exchange("/api/v1/trades", HttpMethod.POST,
                new HttpEntity<>(tradeBody(), headers), TradeResponse.class).getBody().tradeId();

        assertThat(second).isEqualTo(first);   // no new trade created on replay
    }

    private Map<String, Object> tradeBody() {
        return Map.of("instrumentSymbol", "CRUDE-OIL", "side", "BUY", "quantity", 100, "price", 72.55);
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Trade-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class).getBody().id();
    }

    private void registerInstrument(UUID tenantId, String symbol) {
        // Instruments have no user FK, so a token with a random userId is fine here.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, Role.RISK_MANAGER));
        rest.exchange("/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", symbol, "positionLimit", 10_000), headers), String.class);
    }

    /** Trades reference a real user (trades.user_id FK), so create one and issue its token. */
    private HttpHeaders traderHeaders(UUID tenantId, String idempotencyKey) {
        UUID userId = createUser(tenantId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(userId, tenantId, Role.TRADER));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private UUID createUser(UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return rest.exchange("/admin/tenants/" + tenantId + "/users", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", UUID.randomUUID() + "@t.com", "password", "secret123", "role", "TRADER"),
                        headers),
                UserResponse.class).getBody().id();
    }
}
