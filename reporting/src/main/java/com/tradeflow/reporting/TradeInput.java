package com.tradeflow.reporting;

import com.tradeflow.common.trade.Side;

import java.math.BigDecimal;

/** A single trade fed to the P&L calculator (side, quantity, price) — no persistence coupling. */
public record TradeInput(Side side, long quantity, BigDecimal price) {
}
