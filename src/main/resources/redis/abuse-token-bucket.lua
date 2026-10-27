-- IP and subject buckets are independent. No subject strike is attributed to an IP rejection.
-- All decisions, quota reservations, strike pruning and TTL updates are atomic.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local rate = tonumber(ARGV[1])
local multiplier = tonumber(ARGV[2])
local window = tonumber(ARGV[3]) * 1000
local cooldown = tonumber(ARGV[4]) * 1000
local threshold = tonumber(ARGV[5])

local function check(offset, capacity, scope)
    local remaining = redis.call('PTTL', KEYS[offset + 2])
    if remaining > 0 then return {2, math.ceil(remaining / 1000), scope} end
    local state = redis.call('HMGET', KEYS[offset], 'tokens', 'at')
    local tokens = tonumber(state[1]) or capacity
    local at = tonumber(state[2]) or now
    tokens = math.min(capacity, tokens + math.max(0, now - at) * capacity / 60000)
    redis.call('HSET', KEYS[offset], 'tokens', tostring(tokens), 'at', now)
    redis.call('PEXPIRE', KEYS[offset], 120000)
    if tokens >= 1 then return nil end
    local minute = math.floor(now / 60000)
    redis.call('ZREMRANGEBYSCORE', KEYS[offset + 1], '-inf', now - window)
    -- One strike per distinct minute; score is the latest violation in that minute.
    redis.call('ZADD', KEYS[offset + 1], now, tostring(minute))
    redis.call('PEXPIRE', KEYS[offset + 1], window)
    if redis.call('ZCARD', KEYS[offset + 1]) >= threshold then
        redis.call('SET', KEYS[offset + 2], '1', 'PX', cooldown, 'NX')
        redis.call('DEL', KEYS[offset + 1])
        return {2, math.ceil(cooldown / 1000), scope}
    end
    return {1, math.max(1, math.ceil((1 - tokens) * 60 / capacity)), scope}
end

-- Check cooldowns before touching either bucket. Rejected retries never extend cooldown.
for _, pair in ipairs({{3, 1}, {6, 2}}) do
    if multiplier > 0 or pair[2] == 2 then
        local ttl = redis.call('PTTL', KEYS[pair[1]])
        if ttl > 0 then return {2, math.ceil(ttl / 1000), pair[2]} end
    end
end
if multiplier > 0 then
    local rejected = check(1, rate * multiplier, 1)
    if rejected then return rejected end
end
local rejected = check(4, rate, 2)
if rejected then return rejected end
if multiplier > 0 then redis.call('HINCRBYFLOAT', KEYS[1], 'tokens', -1) end
redis.call('HINCRBYFLOAT', KEYS[4], 'tokens', -1)
return {0, 0, 0}
