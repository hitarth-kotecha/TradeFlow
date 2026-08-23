package com.tradeflow.common.trade;

/**
 * Trade direction. Shared in {@code common} because it appears in both the trade domain
 * (trade-ingestion) and the event contract consumed by risk-engine.
 */
public enum Side {
    BUY,
    SELL;

    /** Signed multiplier applied to quantity when updating a net position (BUY adds, SELL subtracts). */
    public int sign() {
        return this == BUY ? 1 : -1;
    }
}
