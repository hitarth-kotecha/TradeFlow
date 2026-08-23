package com.tradeflow.gateway.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Per-tenant sliding-window rate limiter (Concept 2). The check-count-add sequence runs inside one
 * Lua script, so single-threaded Redis makes it atomic — two concurrent requests can't both pass the
 * count check (§8.3, same atomicity lesson as the position store).
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    @SuppressWarnings("unchecked")
    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = RedisScript.of(new ClassPathResource("redis/sliding_window_rate_limit.lua"), List.class);
    }

    /** @return true if the request is allowed; false if the tenant is over its limit for the window. */
    public boolean tryAcquire(UUID tenantId, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;

        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(
                script,
                List.of("tenant:" + tenantId + ":rl"),
                Long.toString(now), Long.toString(windowMs), Integer.toString(limit),
                UUID.randomUUID().toString(), Integer.toString(windowSeconds));

        return result.get(0) == 1L;
    }
}
