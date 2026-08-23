-- updatePositionAndEvaluate (DD-06). Runs atomically inside Redis (single-threaded execution),
-- so dedupe + apply + limit-check cannot interleave with any other command.
--
-- KEYS[1] = position key       tenant:{id}:pos:{symbol}
-- KEYS[2] = applied-set key    tenant:{id}:applied:{tradeId}
-- ARGV[1] = signed delta (BUY = +qty, SELL = -qty)
-- ARGV[2] = absolute position limit
-- ARGV[3] = applied-set TTL (seconds)
-- returns { newPosition, breached(0|1), alreadyApplied(0|1) }

-- Idempotency: if we've already applied this tradeId, do nothing and report the current position.
if redis.call('EXISTS', KEYS[2]) == 1 then
    local current = redis.call('GET', KEYS[1])
    local pos = current and tonumber(current) or 0
    return { pos, 0, 1 }
end

local newPosition = redis.call('INCRBY', KEYS[1], ARGV[1])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])

local breached = 0
if math.abs(newPosition) > tonumber(ARGV[2]) then
    breached = 1
end

return { newPosition, breached, 0 }
