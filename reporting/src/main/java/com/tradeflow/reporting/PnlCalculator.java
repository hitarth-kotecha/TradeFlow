package com.tradeflow.reporting;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Weighted-average-cost P&L (§14.2). A pure fold over a single instrument's trades in chronological
 * order — no I/O — so every scenario is a plain assertion. Realized accrues on closing trades;
 * unrealized is the open position marked to market. Handles partial closes, full closes, and flips.
 */
@Component
public class PnlCalculator {

    private static final int MONEY_SCALE = 4;   // matches NUMERIC(_,4)
    private static final int CALC_SCALE = 8;    // extra precision for intermediate avgCost division

    public PnlResult compute(List<TradeInput> trades, BigDecimal markPrice) {
        long position = 0;
        BigDecimal avgCost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;

        for (TradeInput trade : trades) {
            long signedQty = (long) trade.side().sign() * trade.quantity();
            long absPosition = Math.abs(position);

            if (position == 0 || Long.signum(position) == Long.signum(signedQty)) {
                // Opening or increasing exposure → blend the new lot into the average cost.
                BigDecimal newAbs = BigDecimal.valueOf(absPosition + trade.quantity());
                avgCost = avgCost.multiply(BigDecimal.valueOf(absPosition))
                        .add(trade.price().multiply(BigDecimal.valueOf(trade.quantity())))
                        .divide(newAbs, CALC_SCALE, RoundingMode.HALF_UP);
                position += signedQty;
            } else {
                // Reducing / closing → realize P&L on the closed quantity.
                long closingQty = Math.min(trade.quantity(), absPosition);
                int positionSign = Long.signum(position);
                realized = realized.add(
                        trade.price().subtract(avgCost)
                                .multiply(BigDecimal.valueOf(closingQty))
                                .multiply(BigDecimal.valueOf(positionSign)));
                position += signedQty;

                if (position != 0 && Long.signum(position) != positionSign) {
                    avgCost = trade.price();          // flipped: leftover opens at this trade's price
                } else if (position == 0) {
                    avgCost = BigDecimal.ZERO;         // flat: no cost basis
                }
            }
        }

        boolean markMissing = markPrice == null;
        BigDecimal unrealized = markMissing
                ? BigDecimal.ZERO
                : markPrice.subtract(avgCost).multiply(BigDecimal.valueOf(position));

        return new PnlResult(
                realized.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                unrealized.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                position,
                avgCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                markMissing);
    }
}
