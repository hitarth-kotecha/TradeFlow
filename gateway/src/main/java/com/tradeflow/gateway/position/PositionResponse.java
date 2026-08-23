package com.tradeflow.gateway.position;

import com.tradeflow.gateway.instrument.Instrument;

/** Net position for one instrument with limit utilization as a percentage (§10.5). */
public record PositionResponse(String instrumentSymbol, long netPosition, long positionLimit, double utilizationPct) {

    public static PositionResponse of(Instrument instrument, long netPosition) {
        long limit = instrument.getPositionLimit();
        double utilization = limit == 0 ? 0.0 : Math.abs(netPosition) * 100.0 / limit;
        return new PositionResponse(instrument.getSymbol(), netPosition, limit, utilization);
    }
}
