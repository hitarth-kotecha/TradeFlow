package com.tradeflow.gateway.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data derives the SQL from the method name — no implementation needed. */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByName(String name);

    boolean existsByName(String name);
}
