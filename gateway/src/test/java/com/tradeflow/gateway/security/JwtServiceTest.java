package com.tradeflow.gateway.security;

import com.tradeflow.gateway.user.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test — no Spring context, no DB. Proves the sign/verify round-trip and tamper detection (Concept 1). */
class JwtServiceTest {

    private final JwtService jwt =
            new JwtService(new JwtProperties("unit-test-secret-that-is-long-enough-for-hs256!!", 3600));

    @Test
    void issuesAndVerifiesRoundTrip() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        String token = jwt.issue(user, tenant, Role.TRADER);
        JwtPrincipal principal = jwt.verify(token);

        assertThat(principal.userId()).isEqualTo(user);
        assertThat(principal.tenantId()).isEqualTo(tenant);
        assertThat(principal.role()).isEqualTo(Role.TRADER);
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwt.issue(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
        // Flip the final character: the recomputed signature will no longer match.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> jwt.verify(tampered)).isInstanceOf(JwtException.class);
    }
}
