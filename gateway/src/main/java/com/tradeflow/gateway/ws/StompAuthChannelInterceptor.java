package com.tradeflow.gateway.ws;

import com.tradeflow.gateway.security.JwtPrincipal;
import com.tradeflow.gateway.security.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Secures the STOMP channel (Concept 1). On CONNECT, authenticate the JWT and attach the tenant.
 * On SUBSCRIBE, allow only a tenant's own topic — the real-time extension of tenant isolation
 * (FR-ALERT-03): a client can never eavesdrop on another tenant's alerts.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String TOPIC_PREFIX = "/topic/tenant.";
    private static final String TOPIC_SUFFIX = ".alerts";

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Missing bearer token on CONNECT");
        }
        JwtPrincipal principal = jwtService.verify(header.substring("Bearer ".length()));
        accessor.setUser(new StompPrincipal(principal.userId(), principal.tenantId()));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            throw new MessagingException("Not authenticated");
        }
        UUID topicTenant = tenantOf(accessor.getDestination());
        if (topicTenant == null || !topicTenant.equals(principal.tenantId())) {
            throw new MessagingException("Cannot subscribe to another tenant's topic");
        }
    }

    private UUID tenantOf(String destination) {
        if (destination == null || !destination.startsWith(TOPIC_PREFIX) || !destination.endsWith(TOPIC_SUFFIX)) {
            return null;
        }
        String id = destination.substring(TOPIC_PREFIX.length(), destination.length() - TOPIC_SUFFIX.length());
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
