package com.tradeflow.position;

/**
 * Result of an atomic position update (DD-06).
 *
 * @param newPosition    the net position after applying the delta
 * @param breached       true if abs(newPosition) exceeds the limit
 * @param alreadyApplied true if this tradeId had already been applied (redelivery → no-op)
 */
public record PositionUpdate(long newPosition, boolean breached, boolean alreadyApplied) {
}
