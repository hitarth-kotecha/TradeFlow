package com.tradeflow.gateway.instrument;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every lookup is tenant-scoped: the {@code tenantId} argument (read from the request's ScopedValue
 * by callers) is baked into each query, which is how cross-tenant reads return "not found".
 */
public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {

    List<Instrument> findByTenantId(UUID tenantId);

    Optional<Instrument> findByTenantIdAndSymbol(UUID tenantId, String symbol);

    boolean existsByTenantIdAndSymbol(UUID tenantId, String symbol);
}
