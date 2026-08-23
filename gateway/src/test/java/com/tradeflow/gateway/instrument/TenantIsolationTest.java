package com.tradeflow.gateway.instrument;

import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.user.Role;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline invariant: strict tenant isolation (FR-TEN-02, Concept 4).
 * T-ISO-01 — a tenant reads only its own data.
 * T-ISO-02 — cross-tenant access returns 404 (never 403), so existence is not leaked.
 */
class TenantIsolationTest extends IntegrationTest {

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void tenantSeesOnlyItsOwnInstruments() {   // T-ISO-01
        UUID tenantA = onboardTenant();
        UUID tenantB = onboardTenant();
        registerInstrument(tenantA, "CRUDE-OIL", 10_000);
        registerInstrument(tenantB, "NAT-GAS", 5_000);

        ResponseEntity<InstrumentResponse[]> response = rest.exchange(
                "/api/v1/instruments", HttpMethod.GET,
                new HttpEntity<>(bearer(tenantA, Role.RISK_MANAGER)), InstrumentResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(InstrumentResponse::symbol)
                .containsExactly("CRUDE-OIL");   // never sees tenant B's NAT-GAS
    }

    @Test
    void crossTenantAccessReturns404NotForbidden() {   // T-ISO-02
        UUID tenantA = onboardTenant();
        UUID tenantB = onboardTenant();
        registerInstrument(tenantB, "NAT-GAS", 5_000);

        // Tenant A asks for tenant B's instrument by symbol.
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/instruments/NAT-GAS", HttpMethod.GET,
                new HttpEntity<>(bearer(tenantA, Role.RISK_MANAGER)), String.class);

        // 404, NOT 403 — tenant A cannot even learn that NAT-GAS exists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        ResponseEntity<TenantResponse> response = rest.exchange(
                "/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Iso-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class);
        return response.getBody().id();
    }

    private void registerInstrument(UUID tenantId, String symbol, long positionLimit) {
        rest.exchange("/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", symbol, "positionLimit", positionLimit),
                        bearer(tenantId, Role.RISK_MANAGER)),
                InstrumentResponse.class);
    }

    private HttpHeaders bearer(UUID tenantId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, role));
        return headers;
    }
}
