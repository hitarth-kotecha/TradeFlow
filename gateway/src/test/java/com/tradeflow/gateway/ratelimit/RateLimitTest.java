package com.tradeflow.gateway.ratelimit;

import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/** T-RATE-01: the request past the per-tenant limit returns 429 with a Retry-After header. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.enabled=true",
                "app.rate-limit.requests=5",
                "app.rate-limit.window-seconds=60",
                "app.risk-engine.enabled=false",
                "app.alert-service.enabled=false",
                "app.outbox.relay.enabled=false"
        })
class RateLimitTest extends IntegrationTest {

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

    @Test
    void returns429AfterTheLimitIsExceeded() {
        UUID tenantId = onboardTenant();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, Role.TRADER));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        for (int i = 0; i < 5; i++) {
            assertThat(rest.exchange("/api/v1/instruments", HttpMethod.GET, request, String.class).getStatusCode())
                    .as("request %d within the limit", i + 1).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> overLimit =
                rest.exchange("/api/v1/instruments", HttpMethod.GET, request, String.class);

        assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overLimit.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(overLimit.getBody()).contains("RATE_LIMITED");
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Rate-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class).getBody().id();
    }
}
