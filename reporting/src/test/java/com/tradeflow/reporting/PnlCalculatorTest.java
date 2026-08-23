package com.tradeflow.reporting;

import com.tradeflow.common.trade.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** WAC P&L scenarios (T-PNL-01..06, §14.2). Pure assertions — hand-computed expected values. */
class PnlCalculatorTest {

    private final PnlCalculator calculator = new PnlCalculator();

    private static TradeInput buy(long qty, String price) {
        return new TradeInput(Side.BUY, qty, new BigDecimal(price));
    }

    private static TradeInput sell(long qty, String price) {
        return new TradeInput(Side.SELL, qty, new BigDecimal(price));
    }

    @Test
    void pureLong() {   // T-PNL-01
        PnlResult r = calculator.compute(List.of(buy(100, "50")), new BigDecimal("55"));
        assertThat(r.netPosition()).isEqualTo(100L);
        assertThat(r.avgCost()).isEqualByComparingTo("50");
        assertThat(r.realizedPnl()).isEqualByComparingTo("0");
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("500");   // 100 * (55-50)
    }

    @Test
    void pureShort() {   // T-PNL-02
        PnlResult r = calculator.compute(List.of(sell(100, "50")), new BigDecimal("45"));
        assertThat(r.netPosition()).isEqualTo(-100L);
        assertThat(r.avgCost()).isEqualByComparingTo("50");
        assertThat(r.realizedPnl()).isEqualByComparingTo("0");
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("500");   // -100 * (45-50)
    }

    @Test
    void weightedAverageCostOnAdd() {
        PnlResult r = calculator.compute(List.of(buy(100, "50"), buy(100, "70")), new BigDecimal("70"));
        assertThat(r.netPosition()).isEqualTo(200L);
        assertThat(r.avgCost()).isEqualByComparingTo("60");          // (50*100 + 70*100)/200
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("2000");  // 200 * (70-60)
    }

    @Test
    void partialClose() {   // T-PNL-03
        PnlResult r = calculator.compute(List.of(buy(100, "50"), sell(40, "60")), new BigDecimal("60"));
        assertThat(r.netPosition()).isEqualTo(60L);
        assertThat(r.avgCost()).isEqualByComparingTo("50");          // unchanged by a reducing trade
        assertThat(r.realizedPnl()).isEqualByComparingTo("400");     // 40 * (60-50)
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("600");   // 60 * (60-50)
    }

    @Test
    void fullClose() {   // T-PNL-04
        PnlResult r = calculator.compute(List.of(buy(100, "50"), sell(100, "60")), new BigDecimal("60"));
        assertThat(r.netPosition()).isZero();
        assertThat(r.avgCost()).isEqualByComparingTo("0");
        assertThat(r.realizedPnl()).isEqualByComparingTo("1000");    // 100 * (60-50)
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    void positionFlipCrossingZero() {   // T-PNL-05
        PnlResult r = calculator.compute(List.of(buy(100, "50"), sell(150, "60")), new BigDecimal("55"));
        assertThat(r.netPosition()).isEqualTo(-50L);                 // flipped long → short
        assertThat(r.avgCost()).isEqualByComparingTo("60");          // leftover opened at the trade price
        assertThat(r.realizedPnl()).isEqualByComparingTo("1000");    // closed 100 * (60-50)
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("250");   // -50 * (55-60)
    }

    @Test
    void missingMarkPrice() {   // T-PNL-06
        PnlResult r = calculator.compute(List.of(buy(100, "50")), null);
        assertThat(r.netPosition()).isEqualTo(100L);
        assertThat(r.markPriceMissing()).isTrue();
        assertThat(r.unrealizedPnl()).isEqualByComparingTo("0");
    }
}
