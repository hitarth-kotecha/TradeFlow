package com.tradeflow.gateway.auth;

import com.tradeflow.gateway.security.JwtService;
import com.tradeflow.gateway.tenant.Tenant;
import com.tradeflow.gateway.tenant.TenantRepository;
import com.tradeflow.gateway.user.User;
import com.tradeflow.gateway.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Authenticates a login and mints a JWT (FR-TEN-04). */
@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String tenantName, String email, String rawPassword) {
        // Same exception for every failure mode → no information leak about which part was wrong.
        Tenant tenant = tenantRepository.findByName(tenantName)
                .orElseThrow(InvalidCredentialsException::new);

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.issue(user.getId(), tenant.getId(), user.getRole());
        return new LoginResponse(token, jwtService.expirySeconds(), user.getRole(), tenant.getId());
    }
}
