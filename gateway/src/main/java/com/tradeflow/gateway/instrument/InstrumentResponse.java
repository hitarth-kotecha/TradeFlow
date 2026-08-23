package com.tradeflow.gateway.instrument;

import java.math.BigDecimal;

public record InstrumentResponse(String symbol, long positionLimit, BigDecimal markPrice) {

    public static InstrumentResponse from(Instrument i) {
        return new InstrumentResponse(i.getSymbol(), i.getPositionLimit(), i.getMarkPrice());
    }
}
