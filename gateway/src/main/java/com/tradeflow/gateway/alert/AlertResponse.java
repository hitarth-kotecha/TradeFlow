package com.tradeflow.gateway.alert;

import com.tradeflow.alert.Alert;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AlertResponse(UUID id, String type, String message, OffsetDateTime createdAt, OffsetDateTime readAt) {

    public static AlertResponse from(Alert a) {
        return new AlertResponse(a.getId(), a.getType(), a.getMessage(), a.getCreatedAt(), a.getReadAt());
    }
}
