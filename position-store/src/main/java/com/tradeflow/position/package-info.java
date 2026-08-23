/**
 * Position store: Redis-backed real-time net positions per tenant+instrument (FR-POS-02),
 * atomic updates via Lua (DD-06), and the Postgres-based rebuild job (FR-POS-05).
 */
package com.tradeflow.position;
