/**
 * Risk engine: consumes {@code trade.submitted}, applies the atomic position update + limit
 * evaluation (DD-06, §13.1) with consumer idempotency (§9.3), and publishes PositionUpdated /
 * RiskBreached events. Coverage-gated to >= 80% (NFR-TEST-01).
 */
package com.tradeflow.risk;
