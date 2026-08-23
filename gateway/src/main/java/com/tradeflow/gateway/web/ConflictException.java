package com.tradeflow.gateway.web;

/** Maps to 409 (duplicate resource, e.g. a tenant name or instrument symbol already taken). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
