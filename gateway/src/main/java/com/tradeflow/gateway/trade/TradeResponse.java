package com.tradeflow.gateway.trade;

import com.tradeflow.common.trade.Side;
import com.tradeflow.trade.Trade;
import com.tradeflow.trade.TradeStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TradeResponse(
        UUID tradeId,
        TradeStatus status,
        String instrumentSymbol,
        Side side,
        long quantity,
        BigDecimal price,
        OffsetDateTime submittedAt) {

    public static TradeResponse from(Trade t) {
        return new TradeResponse(t.getId(), t.getStatus(), t.getInstrumentSymbol(),
                t.getSide(), t.getQuantity(), t.getPrice(), t.getSubmittedAt());
    }
}
