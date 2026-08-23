package com.tradeflow.trade;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

    /** Idempotency lookup: returns the original trade for a previously-seen key (FR-TRADE-06). */
    Optional<Trade> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** Tenant-scoped, paginated listing (FR-TRADE-07). */
    Page<Trade> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Trade> findByTenantIdAndInstrumentSymbol(UUID tenantId, String instrumentSymbol, Pageable pageable);

    /** Trades for one instrument up to a cutoff, in chronological order — the P&L fold input (§14.2). */
    List<Trade> findByTenantIdAndInstrumentSymbolAndSubmittedAtLessThanOrderBySubmittedAtAsc(
            UUID tenantId, String instrumentSymbol, OffsetDateTime cutoff);
}
