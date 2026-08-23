-- Sliding-window rate limiter (§8.3). Atomic in Redis, so concurrent requests can't both slip past.
--
-- KEYS[1] = rate-limit key (a sorted set of request timestamps)  tenant:{id}:rl
-- ARGV[1] = now (ms)
-- ARGV[2] = window (ms)
-- ARGV[3] = limit (max requests per window)
-- ARGV[4] = unique member id for this request
-- ARGV[5] = key TTL (seconds)
-- returns { allowed(0|1), countInWindow }

local now = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- Slide the window: drop entries older than (now - window).
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - windowMs)

local count = redis.call('ZCARD', KEYS[1])
if count >= limit then
    return { 0, count }
end

redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
return { 1, count + 1 }
