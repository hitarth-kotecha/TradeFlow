package com.tradeflow.gateway.user;

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
 * A person belonging to exactly one tenant (FR-TEN-03). Maps to the {@code users} table.
 * The tenant link is stored as a plain {@code tenant_id} UUID (not a JPA relationship) to keep
 * every query explicitly tenant-scoped.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected User() {
        // required by JPA
    }

    /** Create a user. The password must already be BCrypt-hashed by the caller. */
    public static User create(UUID tenantId, String email, String passwordHash, Role role) {
        User u = new User();
        u.id = UUID.randomUUID();
        u.tenantId = tenantId;
        u.email = email;
        u.passwordHash = passwordHash;
        u.role = role;
        return u;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
