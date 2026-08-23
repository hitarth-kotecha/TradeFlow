package com.tradeflow.gateway.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Tenant-aware login lookup: email is unique only within a tenant (§7.2). */
    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);
}
