package com.tradeflow.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted alert (spec §7.8), so a client connecting after a breach can fetch what it missed
 * (FR-ALERT-04). The live push over WebSocket is separate (FR-ALERT-01).
 */
@Entity
@Table(name = "alerts")
public class Alert {

    public static final String TYPE_RISK_BREACH = "RISK_BREACH";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String payload;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column
    private OffsetDateTime readAt;

    protected Alert() {
        // required by JPA
    }

    public static Alert of(UUID tenantId, String type, String message, String payload) {
        Alert a = new Alert();
        a.id = UUID.randomUUID();
        a.tenantId = tenantId;
        a.type = type;
        a.message = message;
        a.payload = payload;
        return a;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public String getPayload() { return payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReadAt() { return readAt; }
}
