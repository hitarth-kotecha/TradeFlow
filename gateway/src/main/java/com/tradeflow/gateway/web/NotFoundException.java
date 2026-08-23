package com.tradeflow.gateway.web;

/** Maps to 404. Also used for cross-tenant access, so existence is never leaked (FR-TEN-02). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
