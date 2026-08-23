package com.tradeflow.gateway.instrument;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Register an instrument for the caller's tenant (FR-INST-01/02). markPrice is optional. */
public record RegisterInstrumentRequest(
        @NotBlank String symbol,
        @Positive long positionLimit,
        @Positive BigDecimal markPrice) {   // @Positive passes when null (optional field)
}
