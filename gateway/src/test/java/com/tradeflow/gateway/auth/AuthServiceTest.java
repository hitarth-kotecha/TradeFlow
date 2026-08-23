package com.tradeflow.gateway.auth;

import com.tradeflow.gateway.security.JwtPrincipal;
import com.tradeflow.gateway.security.JwtProperties;
import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.tenant.RiskMode;
import com.tradeflow.gateway.tenant.Tenant;
import com.tradeflow.gateway.tenant.TenantRepository;
import com.tradeflow.gateway.user.Role;
import com.tradeflow.gateway.user.User;
import com.tradeflow.gateway.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Unit test with Mockito repos + real BCrypt/JWT. Verifies login logic without a database. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwtService =
            new JwtService(new JwtProperties("unit-test-secret-that-is-long-enough-for-hs256!!", 3600));

    private AuthService auth;

    @BeforeEach
    void setUp() {
        auth = new AuthService(tenantRepository, userRepository, encoder, jwtService);
    }

    @Test
    void issuesTokenForValidCredentials() {
        Tenant tenant = Tenant.onboard("Acme", RiskMode.MONITOR);
        User user = User.create(tenant.getId(), "trader@acme.com", encoder.encode("secret"), Role.TRADER);
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndEmail(tenant.getId(), "trader@acme.com")).thenReturn(Optional.of(user));

        LoginResponse response = auth.login("Acme", "trader@acme.com", "secret");

        assertThat(response.token()).isNotBlank();
        assertThat(response.tenantId()).isEqualTo(tenant.getId());
        assertThat(response.role()).isEqualTo(Role.TRADER);

        JwtPrincipal principal = jwtService.verify(response.token());
        assertThat(principal.userId()).isEqualTo(user.getId());
        assertThat(principal.tenantId()).isEqualTo(tenant.getId());
    }

    @Test
    void rejectsWrongPassword() {
        Tenant tenant = Tenant.onboard("Acme", RiskMode.MONITOR);
        User user = User.create(tenant.getId(), "trader@acme.com", encoder.encode("secret"), Role.TRADER);
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndEmail(tenant.getId(), "trader@acme.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> auth.login("Acme", "trader@acme.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsUnknownTenant() {
        when(tenantRepository.findByName("Nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login("Nope", "trader@acme.com", "secret"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
