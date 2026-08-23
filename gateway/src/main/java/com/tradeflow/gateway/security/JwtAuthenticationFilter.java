package com.tradeflow.gateway.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates each request from its {@code Authorization: Bearer <jwt>} header (Concept 2).
 * The identity is built entirely from the token's claims — no database lookup — which is what makes
 * JWT auth stateless. Runs once per request (OncePerRequestFilter).
 *
 * <p>Not a Spring bean on purpose: it is constructed and added to the chain in {@code SecurityConfig}
 * so Spring Boot does not also auto-register it in the main servlet chain (double-registration would
 * run it out of order and drop the authentication).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                JwtPrincipal principal = jwtService.verify(header.substring(BEARER_PREFIX.length()));
                // "ROLE_" prefix so hasRole('RISK_MANAGER') matches (spec §11.2).
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException ex) {
                // Invalid/expired/tampered token: stay unauthenticated; the entry point will return 401.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
