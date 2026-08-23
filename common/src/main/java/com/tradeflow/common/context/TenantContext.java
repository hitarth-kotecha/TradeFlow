package com.tradeflow.common.context;

import java.util.UUID;

/**
 * Ambient request context bound as {@link ScopedValue}s for the duration of request handling (DD-09).
 *
 * <p>Why {@code ScopedValue} and not {@code ThreadLocal}: the binding is immutable, cleans itself up
 * when the enclosing {@code run/call} block exits (no leakage on reused threads), and — crucially —
 * is <b>inherited by subtasks forked inside a {@code StructuredTaskScope}</b>, so {@code tenantId}
 * flows into fan-out reads (account summary, P&amp;L) with no manual passing (FR-SUM-04, NFR-CONC-04).
 *
 * <p>Bound at the edge by {@code TenantContextFilter} (request path) or re-bound from the event
 * payload in Kafka consumers (§11.1 note). Reading an unbound value throws — that's intentional:
 * tenant-scoped code must never run without a tenant.
 */
public final class TenantContext {

    public static final ScopedValue<UUID> TENANT_ID = ScopedValue.newInstance();
    public static final ScopedValue<UUID> USER_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

    private TenantContext() {
    }

    /** The current tenant. Throws if called outside a bound context (fail loud, not silent). */
    public static UUID tenantId() {
        return TENANT_ID.get();
    }

    public static UUID userId() {
        return USER_ID.get();
    }

    public static String traceId() {
        return TRACE_ID.orElse(null);
    }

    public static boolean isBound() {
        return TENANT_ID.isBound();
    }
}
