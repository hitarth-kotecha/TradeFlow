package com.tradeflow.gateway.security;

import com.tradeflow.gateway.user.Role;
import com.tradeflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the security gate (Concept 2): protected endpoints need a valid JWT. */
@Import(SecuredEndpointTest.Probe.class)
class SecuredEndpointTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void rejectsRequestWithoutToken() {
        ResponseEntity<String> response = rest.getForEntity("/test/secured", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsRequestWithValidToken() {
        String token = jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), Role.TRADER);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = rest.exchange(
                "/test/secured", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ok");
    }

    @RestController
    static class Probe {
        @GetMapping("/test/secured")
        String secured() {
            return "ok";
        }
    }
}
