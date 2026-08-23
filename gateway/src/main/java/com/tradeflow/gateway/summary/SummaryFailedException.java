package com.tradeflow.gateway.summary;

/** Raised when any sub-read of the account summary fails (→ 500, FR-SUM-03). */
public class SummaryFailedException extends RuntimeException {
    public SummaryFailedException(Throwable cause) {
        super("Failed to build account summary", cause);
    }
}
