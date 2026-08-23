package com.tradeflow.position;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-backed net position state (FR-POS-02). All mutation goes through one atomic Lua script
 * (DD-06), so concurrent trades never lose an update and redelivered trades never double-apply —
 * the atomicity comes from Redis's single-threaded execution, not from any JVM lock (§12.5).
 */
@Component
public class PositionStore {

    private static final long APPLIED_TTL_SECONDS = 86_400;   // 24h dedupe window (§8.1)

    private final StringRedisTemplate redis;
    private final RedisScript<List> updateScript;

    @SuppressWarnings("unchecked")
    public PositionStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.updateScript = RedisScript.of(
                new ClassPathResource("redis/update_position_and_evaluate.lua"), List.class);
    }

    /** Atomically apply a trade's delta (idempotent on tradeId) and evaluate the limit. */
    public PositionUpdate applyAndEvaluate(UUID tenantId, String symbol, UUID tradeId, long delta, long limit) {
        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(
                updateScript,
                List.of(positionKey(tenantId, symbol), appliedKey(tenantId, tradeId)),
                Long.toString(delta), Long.toString(limit), Long.toString(APPLIED_TTL_SECONDS));

        return new PositionUpdate(result.get(0), result.get(1) == 1L, result.get(2) == 1L);
    }

    /** Current net position for one instrument (0 if none). */
    public long position(UUID tenantId, String symbol) {
        String value = redis.opsForValue().get(positionKey(tenantId, symbol));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Bulk read: net position per symbol in ONE round trip (Redis MGET, §12.6 option a). */
    public Map<String, Long> positions(UUID tenantId, List<String> symbols) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (symbols.isEmpty()) {
            return result;
        }
        List<String> keys = symbols.stream().map(s -> positionKey(tenantId, s)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        for (int i = 0; i < symbols.size(); i++) {
            String value = values == null ? null : values.get(i);
            result.put(symbols.get(i), value == null ? 0L : Long.parseLong(value));
        }
        return result;
    }

    static String positionKey(UUID tenantId, String symbol) {
        return "tenant:" + tenantId + ":pos:" + symbol;
    }

    static String appliedKey(UUID tenantId, UUID tradeId) {
        return "tenant:" + tenantId + ":applied:" + tradeId;
    }
}
