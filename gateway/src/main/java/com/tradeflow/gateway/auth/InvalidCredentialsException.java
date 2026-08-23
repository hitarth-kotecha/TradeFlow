package com.tradeflow.gateway.auth;

/**
 * Thrown for ANY login failure — unknown tenant, unknown email, or wrong password.
 * Using one exception for all cases avoids revealing which part was wrong (§10.1 note).
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
