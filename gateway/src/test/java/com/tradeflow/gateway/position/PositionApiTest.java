package com.tradeflow.gateway.position;

import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.position.PositionStore;
import com.tradeflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /positions reads net positions (Redis) joined with instrument limits (Postgres). */
class PositionApiTest extends IntegrationTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PositionStore positionStore;

    @Test
    void listsNetPositionsForTenant() {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL", 10_000);
        positionStore.applyAndEvaluate(tenantId, "CRUDE-OIL", UUID.randomUUID(), 100, 10_000);

        ResponseEntity<String> response = rest.exchange("/api/v1/positions", HttpMethod.GET,
                new HttpEntity<>(token(tenantId)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("CRUDE-OIL").contains("\"netPosition\":100");
    }

    @Test
    void getsSingleInstrumentPosition() {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "NAT-GAS", 5_000);
        positionStore.applyAndEvaluate(tenantId, "NAT-GAS", UUID.randomUUID(), 250, 5_000);

        ResponseEntity<PositionResponse> response = rest.exchange("/api/v1/positions/NAT-GAS", HttpMethod.GET,
                new HttpEntity<>(token(tenantId)), PositionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().netPosition()).isEqualTo(250L);
        assertThat(response.getBody().positionLimit()).isEqualTo(5_000L);
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Pos-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class).getBody().id();
    }

    private void registerInstrument(UUID tenantId, String symbol, long limit) {
        rest.exchange("/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", symbol, "positionLimit", limit), token(tenantId)), String.class);
    }

    private HttpHeaders token(UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, Role.RISK_MANAGER));
        return headers;
    }
}
