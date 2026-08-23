package com.tradeflow.gateway.report;

import com.tradeflow.gateway.admin.TenantResponse;
import com.tradeflow.gateway.admin.UserResponse;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-PNL-IDEM (FR-PNL-05): running the P&L batch twice for the same date produces one snapshot per
 * instrument (UPSERT), with correct WAC values. Also exercises the full job end-to-end.
 */
class PnlBatchTest extends IntegrationTest {

    private static final String ADMIN_KEY = "dev-platform-admin-key-change-me";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void batchIsIdempotentAndComputesWac() {
        UUID tenantId = onboardTenant();
        registerInstrument(tenantId, "CRUDE-OIL", 10_000, "60");   // mark price 60
        UUID trader = createUser(tenantId);

        submitTrade(tenantId, trader, "BUY", 100, "50");
        submitTrade(tenantId, trader, "SELL", 40, "60");           // realize 40*(60-50)=400, keep 60 @ 50

        String date = LocalDate.now(ZoneOffset.UTC).toString();
        runBatch(date);
        runBatch(date);   // re-run — must not duplicate

        ResponseEntity<PnlReportResponse[]> report = rest.exchange(
                "/api/v1/reports/pnl?date=" + date, HttpMethod.GET,
                new HttpEntity<>(bearer(tenantId, Role.RISK_MANAGER)), PnlReportResponse[].class);

        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getBody()).hasSize(1);   // one row, not two — idempotent
        PnlReportResponse pnl = report.getBody()[0];
        assertThat(pnl.instrumentSymbol()).isEqualTo("CRUDE-OIL");
        assertThat(pnl.netPosition()).isEqualTo(60L);
        assertThat(pnl.realizedPnl()).isEqualByComparingTo("400");
        assertThat(pnl.unrealizedPnl()).isEqualByComparingTo("600");   // 60 * (60-50)
    }

    private void runBatch(String date) {
        // The run endpoint is platform-wide and ADMIN-only; the token's tenant is irrelevant here.
        ResponseEntity<Void> response = rest.exchange(
                "/api/v1/reports/pnl/run?date=" + date, HttpMethod.POST,
                new HttpEntity<>(bearer(UUID.randomUUID(), Role.ADMIN)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private void submitTrade(UUID tenantId, UUID userId, String side, long qty, String price) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(userId, tenantId, Role.TRADER));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        rest.exchange("/api/v1/trades", HttpMethod.POST,
                new HttpEntity<>(Map.of("instrumentSymbol", "CRUDE-OIL", "side", side, "quantity", qty, "price", price),
                        headers),
                String.class);
    }

    private UUID onboardTenant() {
        return rest.exchange("/admin/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Pnl-" + UUID.randomUUID(), "riskMode", "MONITOR"), adminHeaders()),
                TenantResponse.class).getBody().id();
    }

    private void registerInstrument(UUID tenantId, String symbol, long limit, String markPrice) {
        rest.exchange("/api/v1/instruments", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", symbol, "positionLimit", limit, "markPrice", markPrice),
                        bearer(tenantId, Role.RISK_MANAGER)),
                String.class);
    }

    private UUID createUser(UUID tenantId) {
        return rest.exchange("/admin/tenants/" + tenantId + "/users", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", UUID.randomUUID() + "@t.com", "password", "secret123", "role", "TRADER"),
                        adminHeaders()),
                UserResponse.class).getBody().id();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Admin-Key", ADMIN_KEY);
        return headers;
    }

    private HttpHeaders bearer(UUID tenantId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), tenantId, role));
        return headers;
    }
}
