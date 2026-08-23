package com.tradeflow.reporting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PnlSnapshotRepository extends JpaRepository<PnlSnapshot, UUID> {

    /** Latest snapshot for the tenant across all instruments (for the account summary). */
    Optional<PnlSnapshot> findTopByTenantIdOrderBySnapshotDateDesc(UUID tenantId);

    /** All snapshots for a tenant on a date (the P&L report). */
    List<PnlSnapshot> findByTenantIdAndSnapshotDate(UUID tenantId, LocalDate snapshotDate);

    /** Used by the batch's idempotent upsert. */
    Optional<PnlSnapshot> findByTenantIdAndSnapshotDateAndInstrumentSymbol(
            UUID tenantId, LocalDate snapshotDate, String instrumentSymbol);
}
