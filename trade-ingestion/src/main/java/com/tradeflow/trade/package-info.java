/**
 * Trade ingestion: validation (§5.3.1), persistence with status ACCEPTED, the transactional
 * outbox write (DD-03), and the virtual-thread relay that publishes to {@code trade.submitted}.
 */
package com.tradeflow.trade;
