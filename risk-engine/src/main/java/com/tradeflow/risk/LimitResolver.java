package com.tradeflow.risk;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves an instrument's position limit (§13.1). Reads from the instruments table via JDBC so the
 * risk-engine stays independent of the gateway module that owns instrument configuration.
 */
@Component
public class LimitResolver {

    private final JdbcTemplate jdbcTemplate;

    public LimitResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long limitFor(UUID tenantId, String symbol) {
        try {
            Long limit = jdbcTemplate.queryForObject(
                    "SELECT position_limit FROM instruments WHERE tenant_id = ?::uuid AND symbol = ?",
                    Long.class, tenantId.toString(), symbol);
            return limit != null ? limit : Long.MAX_VALUE;
        } catch (EmptyResultDataAccessException e) {
            // Should not happen (ingestion rejects unknown instruments, V4). Fail open: never breach.
            return Long.MAX_VALUE;
        }
    }
}
