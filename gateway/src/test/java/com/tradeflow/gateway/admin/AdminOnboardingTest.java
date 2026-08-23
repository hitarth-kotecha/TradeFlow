package com.tradeflow.gateway.admin;

import com.tradeflow.gateway.auth.LoginResponse;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end: onboard a tenant, create a user, then log in as that user (FR-TEN-01/03/04). */
class AdminOnboardingTest extends IntegrationTest {

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void onboardsTenantCreatesUserAndLogsIn() {
        String tenantName = "Acme-" + UUID.randomUUID();   // unique: the test DB is shared across tests

        ResponseEntity<TenantResponse> tenantResp = rest.exchange(
                "/admin/tenants", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("name", tenantName, "riskMode", "MONITOR"), adminHeaders()),
                TenantResponse.class);
        assertThat(tenantResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = tenantResp.getBody().id();

        ResponseEntity<UserResponse> userResp = rest.exchange(
                "/admin/tenants/" + tenantId + "/users", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "trader@acme.com", "password", "secret123", "role", "TRADER"),
                        adminHeaders()),
                UserResponse.class);
        assertThat(userResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(userResp.getBody().tenantId()).isEqualTo(tenantId);

        ResponseEntity<LoginResponse> loginResp = rest.postForEntity(
                "/api/v1/auth/login",
                Map.of("tenantName", tenantName, "email", "trader@acme.com", "password", "secret123"),
                LoginResponse.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody().token()).isNotBlank();
        assertThat(loginResp.getBody().tenantId()).isEqualTo(tenantId);
        assertThat(loginResp.getBody().role()).isEqualTo(Role.TRADER);
    }

    @Test
    void rejectsWrongPlatformAdminKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", "wrong-key");

        ResponseEntity<String> response = rest.exchange(
                "/admin/tenants", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "ShouldFail"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return headers;
    }
}
