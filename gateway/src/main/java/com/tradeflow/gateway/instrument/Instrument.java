package com.tradeflow.gateway.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tradable instrument configured per tenant, carrying its position limit and optional mark price
 * (FR-INST-01/02/05). Maps to the {@code instruments} table; unique on {@code (tenant_id, symbol)}.
 */
@Entity
@Table(name = "instruments")
public class Instrument {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private long positionLimit;

    @Column
    private BigDecimal markPrice;   // nullable until set (FR-INST-05)

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Instrument() {
        // required by JPA
    }

    public static Instrument register(UUID tenantId, String symbol, long positionLimit, BigDecimal markPrice) {
        Instrument i = new Instrument();
        i.id = UUID.randomUUID();
        i.tenantId = tenantId;
        i.symbol = symbol;
        i.positionLimit = positionLimit;
        i.markPrice = markPrice;
        return i;
    }

    /** Update mutable config; applies to future trades only (FR-INST-04). */
    public void updateConfig(long positionLimit, BigDecimal markPrice) {
        this.positionLimit = positionLimit;
        this.markPrice = markPrice;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getSymbol() { return symbol; }
    public long getPositionLimit() { return positionLimit; }
    public BigDecimal getMarkPrice() { return markPrice; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
