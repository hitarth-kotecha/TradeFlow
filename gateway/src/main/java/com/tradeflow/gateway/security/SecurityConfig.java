package com.tradeflow.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.gateway.ratelimit.RateLimitFilter;
import com.tradeflow.gateway.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security filter chain (Concept 2). Stateless (no HTTP sessions — every request re-authenticates
 * from its JWT), public routes explicitly permitted, everything else requires a valid token.
 * {@code @EnableMethodSecurity} turns on {@code @PreAuthorize} for role checks (§11.2).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   SecurityErrorResponder securityErrorResponder,
                                                   RateLimiter rateLimiter,
                                                   ObjectMapper objectMapper,
                                                   @Value("${app.rate-limit.enabled:true}") boolean rateLimitEnabled,
                                                   @Value("${app.rate-limit.requests:100}") int rateLimitRequests,
                                                   @Value("${app.rate-limit.window-seconds:10}") int rateLimitWindow)
            throws Exception {
        // Constructed here (not beans) so they exist ONLY in this chain, never auto-registered globally.
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService);
        TenantContextFilter tenantContextFilter = new TenantContextFilter();
        http
                // No browser sessions/forms, so CSRF protection is not applicable (we use Bearer tokens).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/admin/**",               // guarded by the platform-admin key, not a JWT
                                "/ws/**",                  // STOMP handshake; auth happens on the CONNECT frame
                                "/error",                  // let error responses keep their real status (not 401)
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/**")            // local convenience; lock down in prod
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorResponder)   // 401
                        .accessDeniedHandler(securityErrorResponder))       // 403
                // Authenticate from the JWT before Spring's username/password filter position,
                // then bind the tenant context right after (needs the authenticated principal).
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantContextFilter, JwtAuthenticationFilter.class);

        // Rate limit after the tenant is bound (so it's keyed per tenant). Off in tests without Redis.
        if (rateLimitEnabled) {
            http.addFilterAfter(
                    new RateLimitFilter(rateLimiter, objectMapper, rateLimitRequests, rateLimitWindow),
                    TenantContextFilter.class);
        }
        return http.build();
    }
}
