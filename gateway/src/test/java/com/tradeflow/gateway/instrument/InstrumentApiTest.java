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

/** Instrument CRUD + role rules, scoped to the caller's tenant (FR-INST-01..03, §11.2). */
class InstrumentApiTest extends IntegrationTest {

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void riskManagerRegistersAndReadsInstrument() {
        UUID tenantId = onboardTenant();
        HttpHeaders rm = bearer(tenantId, Role.RISK_MANAGER);

        ResponseEntity<InstrumentResponse> created = rest.exchange(
                "/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", "CRUDE-OIL", "positionLimit", 10000, "markPrice", 72.55), rm),
                InstrumentResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().symbol()).isEqualTo("CRUDE-OIL");

        ResponseEntity<InstrumentResponse> fetched = rest.exchange(
                "/api/v1/instruments/CRUDE-OIL", HttpMethod.GET, new HttpEntity<>(rm), InstrumentResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().positionLimit()).isEqualTo(10000);
    }

    @Test
    void traderCannotRegisterInstrument() {
        UUID tenantId = onboardTenant();
        HttpHeaders trader = bearer(tenantId, Role.TRADER);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", "NAT-GAS", "positionLimit", 5000), trader), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownInstrumentReturns404() {
        UUID tenantId = onboardTenant();
        HttpHeaders rm = bearer(tenantId, Role.RISK_MANAGER);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/instruments/UNKNOWN", HttpMethod.GET, new HttpEntity<>(rm), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID onboardTenant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        ResponseEntity<TenantResponse> response = rest.exchange(
                "/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Inst-" + UUID.randomUUID(), "riskMode", "MONITOR"), headers),
                TenantResponse.class);
        return response.getBody().id();
    }

    private HttpHeaders bearer(UUID tenantId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, role));
        return headers;
    }
}
