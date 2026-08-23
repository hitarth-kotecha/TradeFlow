package com.tradeflow.gateway.instrument;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Update an instrument's limit and mark price; applies to future trades only (FR-INST-04). */
public record UpdateInstrumentRequest(
        @Positive long positionLimit,
        @Positive BigDecimal markPrice) {
}
