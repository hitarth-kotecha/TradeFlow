package com.tradeflow.gateway.security;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves TenantContextFilter binds the tenant from the JWT so a handler reads it via ScopedValue (DD-09). */
@Import(TenantContextTest.Probe.class)
class TenantContextTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void bindsTenantIdFromTokenForTheRequest() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = jwtService.issue(userId, tenantId, Role.TRADER);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = rest.exchange(
                "/test/tenant", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getBody()).isEqualTo(tenantId.toString());
    }

    @RestController
    static class Probe {
        @GetMapping("/test/tenant")
        String currentTenant() {
            // Reads the ScopedValue bound by TenantContextFilter — no parameter threaded in.
            return TenantContext.tenantId().toString();
        }
    }
}
