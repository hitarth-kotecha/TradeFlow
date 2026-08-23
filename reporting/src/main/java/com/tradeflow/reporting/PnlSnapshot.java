package com.tradeflow.reporting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A point-in-time P&L record (spec §7.7). Unique on {@code (tenant_id, snapshot_date, symbol)} so a
 * re-run of the batch for a date UPSERTs rather than duplicates (FR-PNL-05).
 */
@Entity
@Table(name = "pnl_snapshots")
public class PnlSnapshot {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private String instrumentSymbol;

    @Column(nullable = false)
    private BigDecimal realizedPnl;

    @Column(nullable = false)
    private BigDecimal unrealizedPnl;

    @Column(nullable = false)
    private long netPosition;

    @Column(nullable = false)
    private BigDecimal avgCost;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PnlSnapshot() {
        // required by JPA
    }

    public static PnlSnapshot of(UUID tenantId, LocalDate snapshotDate, String instrumentSymbol,
                                 BigDecimal realizedPnl, BigDecimal unrealizedPnl, long netPosition,
                                 BigDecimal avgCost) {
        PnlSnapshot s = new PnlSnapshot();
        s.id = UUID.randomUUID();
        s.tenantId = tenantId;
        s.snapshotDate = snapshotDate;
        s.instrumentSymbol = instrumentSymbol;
        s.realizedPnl = realizedPnl;
        s.unrealizedPnl = unrealizedPnl;
        s.netPosition = netPosition;
        s.avgCost = avgCost;
        return s;
    }

    /** Overwrite the computed values on a re-run for the same (tenant, date, symbol) — idempotent (FR-PNL-05). */
    public void update(BigDecimal realizedPnl, BigDecimal unrealizedPnl, long netPosition, BigDecimal avgCost) {
        this.realizedPnl = realizedPnl;
        this.unrealizedPnl = unrealizedPnl;
        this.netPosition = netPosition;
        this.avgCost = avgCost;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public String getInstrumentSymbol() { return instrumentSymbol; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public long getNetPosition() { return netPosition; }
    public BigDecimal getAvgCost() { return avgCost; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
