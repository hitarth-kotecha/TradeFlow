package com.tradeflow.gateway.admin;

import com.tradeflow.gateway.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotNull Role role) {
}
