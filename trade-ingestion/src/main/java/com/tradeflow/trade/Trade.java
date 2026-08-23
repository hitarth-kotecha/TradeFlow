package com.tradeflow.trade;

import com.tradeflow.common.trade.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted trade (spec §7.4, FR-TRADE-03). Owned by the trade-ingestion module.
 * The optional idempotencyKey is uniquely indexed per tenant to make submission retry-safe.
 */
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String instrumentSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeStatus status;

    @Column
    private String idempotencyKey;

    @Column(nullable = false)
    private OffsetDateTime submittedAt;

    protected Trade() {
        // required by JPA
    }

    /** Create an ACCEPTED trade with a server-generated id and submission timestamp (UTC). */
    public static Trade accepted(UUID tenantId, UUID userId, String instrumentSymbol,
                                 Side side, long quantity, BigDecimal price, String idempotencyKey) {
        Trade t = new Trade();
        t.id = UUID.randomUUID();
        t.tenantId = tenantId;
        t.userId = userId;
        t.instrumentSymbol = instrumentSymbol;
        t.side = side;
        t.quantity = quantity;
        t.price = price;
        t.status = TradeStatus.ACCEPTED;
        t.idempotencyKey = idempotencyKey;
        t.submittedAt = OffsetDateTime.now();
        return t;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getInstrumentSymbol() { return instrumentSymbol; }
    public Side getSide() { return side; }
    public long getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public TradeStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
}
