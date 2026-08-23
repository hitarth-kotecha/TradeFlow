package com.tradeflow.trade;

/** Outbox row dispatch state (mirrors the CHECK constraint on outbox.status). */
public enum OutboxStatus {
    PENDING,
    DISPATCHED
}
