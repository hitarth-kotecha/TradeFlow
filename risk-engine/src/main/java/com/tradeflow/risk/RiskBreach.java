package com.tradeflow.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A recorded limit breach (spec §7.6). Unique on {@code (tenant_id, trade_id)} so re-processing the
 * same trade can never create a duplicate breach (FR-RISK-03).
 */
@Entity
@Table(name = "risk_breaches")
public class RiskBreach {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID tradeId;

    @Column(nullable = false)
    private String instrumentSymbol;

    @Column(nullable = false)
    private long netPosition;

    @Column(nullable = false)
    private long positionLimit;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    protected RiskBreach() {
        // required by JPA
    }

    public static RiskBreach of(UUID tenantId, UUID tradeId, String instrumentSymbol,
                                long netPosition, long positionLimit) {
        RiskBreach b = new RiskBreach();
        b.id = UUID.randomUUID();
        b.tenantId = tenantId;
        b.tradeId = tradeId;
        b.instrumentSymbol = instrumentSymbol;
        b.netPosition = netPosition;
        b.positionLimit = positionLimit;
        return b;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTradeId() { return tradeId; }
    public String getInstrumentSymbol() { return instrumentSymbol; }
    public long getNetPosition() { return netPosition; }
    public long getPositionLimit() { return positionLimit; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
}
