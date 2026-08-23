package com.tradeflow.gateway.admin;

import com.tradeflow.gateway.user.Role;
import com.tradeflow.gateway.user.User;

import java.util.UUID;

/** Never exposes the password hash. */
public record UserResponse(UUID id, UUID tenantId, String email, Role role) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getTenantId(), u.getEmail(), u.getRole());
    }
}
