package com.tradeflow;

import com.tradeflow.gateway.security.JwtService;
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

/**
 * T-VT-01 (NFR-CONC-01): an HTTP request must be handled on a virtual thread.
 * The probe endpoint now sits behind the security filter chain, so the request carries a
 * self-issued JWT (the filter authenticates from claims alone — no DB user needed).
 */
@Import(VirtualThreadTest.ThreadProbeController.class)
class VirtualThreadTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void requestIsHandledOnAVirtualThread() {
        String token = jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), Role.TRADER);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Boolean> response = rest.exchange(
                "/test/thread-kind", HttpMethod.GET, new HttpEntity<>(headers), Boolean.class);

        assertThat(response.getBody())
                .as("the request handler should run on a virtual thread")
                .isTrue();
    }

    @RestController
    static class ThreadProbeController {
        @GetMapping("/test/thread-kind")
        boolean threadKind() {
            return Thread.currentThread().isVirtual();
        }
    }
}
