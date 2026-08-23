package com.tradeflow.gateway.user;

import com.tradeflow.gateway.tenant.TenantRepository;
import com.tradeflow.gateway.web.ConflictException;
import com.tradeflow.gateway.web.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Creates users within a tenant (FR-TEN-03). Passwords are BCrypt-hashed before storage. */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User create(UUID tenantId, String email, String rawPassword, Role role) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new NotFoundException("Tenant not found: " + tenantId);
        }
        if (userRepository.findByTenantIdAndEmail(tenantId, email).isPresent()) {
            throw new ConflictException("Email already registered in this tenant: " + email);
        }
        User user = User.create(tenantId, email, passwordEncoder.encode(rawPassword), role);
        return userRepository.save(user);
    }
}
