package com.tradeflow.gateway.security;

import com.tradeflow.common.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds the tenant context for the request (DD-09). Runs AFTER {@link JwtAuthenticationFilter}, so the
 * authenticated {@link JwtPrincipal} is available. Wraps the rest of the chain in a scoped-value
 * binding, so downstream repositories/services read {@code TenantContext.tenantId()} and any forked
 * subtasks inherit it. The binding ends automatically when the block returns — no cleanup.
 *
 * <p>Not a Spring bean on purpose (see {@link JwtAuthenticationFilter}): constructed in
 * {@code SecurityConfig} to avoid Spring Boot double-registering it in the main servlet chain.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof JwtPrincipal principal) {
            String traceId = resolveTraceId(request);
            try {
                ScopedValue.where(TenantContext.TENANT_ID, principal.tenantId())
                        .where(TenantContext.USER_ID, principal.userId())
                        .where(TenantContext.TRACE_ID, traceId)
                        .call(() -> {
                            chain.doFilter(request, response);
                            return null;
                        });
            } catch (ServletException | IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException(e);
            }
        } else {
            // Public endpoints (login, swagger) run with no tenant bound.
            chain.doFilter(request, response);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String header = request.getHeader("X-Trace-Id");
        return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
    }
}
