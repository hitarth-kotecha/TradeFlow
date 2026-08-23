package com.tradeflow.reporting;

import java.math.BigDecimal;

/**
 * P&L for one instrument after folding its trades (spec §14.2).
 *
 * @param realizedPnl      locked in from closing trades
 * @param unrealizedPnl    mark-to-market on the open position (0 if mark price missing)
 * @param netPosition      signed open quantity
 * @param avgCost          weighted-average cost of the open position
 * @param markPriceMissing true if no mark price was available (§13.3)
 */
public record PnlResult(
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        long netPosition,
        BigDecimal avgCost,
        boolean markPriceMissing) {
}
