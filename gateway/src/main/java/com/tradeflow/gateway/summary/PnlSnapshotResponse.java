package com.tradeflow.gateway.summary;

import com.tradeflow.reporting.PnlSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PnlSnapshotResponse(
        LocalDate snapshotDate,
        String instrumentSymbol,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        long netPosition) {

    public static PnlSnapshotResponse from(PnlSnapshot s) {
        return new PnlSnapshotResponse(s.getSnapshotDate(), s.getInstrumentSymbol(),
                s.getRealizedPnl(), s.getUnrealizedPnl(), s.getNetPosition());
    }
}
