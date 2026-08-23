package com.tradeflow.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.context.TenantContext;
import com.tradeflow.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * Enforces the per-tenant rate limit (FR-RATE-01/02). Runs after the tenant context is bound;
 * public endpoints (no tenant) are not limited. Over the limit → 429 + Retry-After (§15).
 * Not a Spring bean — constructed in SecurityConfig to avoid double-registration.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final int limit;
    private final int windowSeconds;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper, int limit, int windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!TenantContext.isBound()) {
            chain.doFilter(request, response);   // unauthenticated/public routes aren't tenant-limited
            return;
        }

        if (rateLimiter.tryAcquire(TenantContext.tenantId(), limit, windowSeconds)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(windowSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(OffsetDateTime.now(), HttpStatus.TOO_MANY_REQUESTS.value(),
                "RATE_LIMITED", "Rate limit exceeded", request.getRequestURI(), null);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
