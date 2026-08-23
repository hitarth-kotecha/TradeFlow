package com.tradeflow.common.error;

import java.time.OffsetDateTime;

/**
 * The standard error body returned by every endpoint (spec §15.1). Kept in {@code common}
 * so all modules produce an identical shape.
 */
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        String traceId) {
}
