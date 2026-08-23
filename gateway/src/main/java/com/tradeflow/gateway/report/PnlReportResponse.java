package com.tradeflow.gateway.report;

import com.tradeflow.reporting.PnlSnapshot;

import java.math.BigDecimal;

public record PnlReportResponse(
        String instrumentSymbol,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        long netPosition,
        BigDecimal avgCost) {

    public static PnlReportResponse from(PnlSnapshot s) {
        return new PnlReportResponse(s.getInstrumentSymbol(), s.getRealizedPnl(),
                s.getUnrealizedPnl(), s.getNetPosition(), s.getAvgCost());
    }
}
