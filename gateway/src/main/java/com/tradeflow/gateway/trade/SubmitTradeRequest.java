package com.tradeflow.gateway.trade;

import com.tradeflow.common.trade.Side;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Trade submission payload (spec §10.4). Bean Validation covers the trade rules:
 * V1 quantity &gt; 0 (@Positive), V5 quantity &le; 1,000,000 (@Max),
 * V2 price &gt; 0 with &le; 4 decimals (@Positive + @Digits), V3 side is a valid enum.
 */
public record SubmitTradeRequest(
        @NotBlank String instrumentSymbol,
        @NotNull Side side,
        @Positive @Max(1_000_000) long quantity,
        @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal price) {
}
