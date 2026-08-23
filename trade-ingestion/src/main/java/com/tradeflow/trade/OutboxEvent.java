package com.tradeflow.trade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pending domain event written in the SAME transaction as its aggregate (the transactional
 * outbox, DD-03). The relay polls PENDING rows, publishes to Kafka, then marks them DISPATCHED.
 * The {@code payload} is stored as JSONB.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column
    private OffsetDateTime dispatchedAt;

    protected OutboxEvent() {
        // required by JPA
    }

    public static OutboxEvent pending(String aggregateType, UUID aggregateId, String topic, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.id = UUID.randomUUID();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.topic = topic;
        e.payload = payload;
        e.status = OutboxStatus.PENDING;
        return e;
    }

    /** Called by the relay after a successful publish. */
    public void markDispatched() {
        this.status = OutboxStatus.DISPATCHED;
        this.dispatchedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
}
