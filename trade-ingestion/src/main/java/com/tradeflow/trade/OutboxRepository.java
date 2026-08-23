package com.tradeflow.trade;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** The relay polls the oldest undispatched rows first, in bounded batches. */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Limit limit);
}
