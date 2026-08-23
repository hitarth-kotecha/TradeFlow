package com.tradeflow.gateway.tenant;

/**
 * Per-tenant risk handling mode (DD-04).
 * MONITOR (default): accept breaching trades, flag asynchronously.
 * BLOCK: reject the breaching trade synchronously at ingestion.
 */
public enum RiskMode {
    BLOCK,
    MONITOR
}
