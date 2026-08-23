package com.tradeflow.gateway.summary;

import com.tradeflow.gateway.position.PositionResponse;
import com.tradeflow.gateway.risk.BreachResponse;

import java.util.List;

/** Composite view for a tenant, assembled from three concurrent sub-reads (spec §10.9). */
public record AccountSummary(
        List<PositionResponse> positions,
        List<BreachResponse> recentBreaches,
        PnlSnapshotResponse latestPnl) {
}
