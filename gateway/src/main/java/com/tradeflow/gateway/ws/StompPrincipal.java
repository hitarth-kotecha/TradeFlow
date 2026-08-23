package com.tradeflow.gateway.ws;

import java.security.Principal;
import java.util.UUID;

/** The identity attached to a STOMP session after the CONNECT frame is authenticated. */
public record StompPrincipal(UUID userId, UUID tenantId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
