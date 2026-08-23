/**
 * TradeFlow shared kernel.
 *
 * <p>This module holds the types every other module agrees on: event schemas
 * (the JSON contracts published to Kafka, spec §9.2), shared DTOs, the
 * {@code ScopedValue}-based tenant context (DD-09), the standard error body
 * (spec §15), and common exceptions.
 *
 * <p>Dependency rule: {@code common} depends on no other TradeFlow module —
 * every arrow in the module graph points here. Keeping it dependency-light is
 * what makes the future microservice split (DD-01) mechanical.
 */
package com.tradeflow.common;
