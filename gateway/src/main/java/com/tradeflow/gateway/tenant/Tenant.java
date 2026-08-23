package com.tradeflow.gateway.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A trading firm — the hard isolation boundary (FR-TEN-01). Maps to the {@code tenants} table.
 *
 * <p>Column names are derived from field names by Spring Boot's snake_case naming strategy
 * (e.g. {@code riskMode} -> {@code risk_mode}), so most columns need no explicit @Column(name=...).
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskMode riskMode;

    @Column(nullable = false)
    private int rateLimitPerWindow;

    @Column(nullable = false)
    private int rateWindowSeconds;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Tenant() {
        // required by JPA
    }

    /** Onboard a new tenant with platform defaults (spec §7.1). Server generates the immutable id. */
    public static Tenant onboard(String name, RiskMode riskMode) {
        Tenant t = new Tenant();
        t.id = UUID.randomUUID();
        t.name = name;
        t.status = TenantStatus.ACTIVE;
        t.riskMode = riskMode;
        t.rateLimitPerWindow = 100;
        t.rateWindowSeconds = 10;
        return t;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public TenantStatus getStatus() { return status; }
    public RiskMode getRiskMode() { return riskMode; }
    public int getRateLimitPerWindow() { return rateLimitPerWindow; }
    public int getRateWindowSeconds() { return rateWindowSeconds; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
