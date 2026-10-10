package com.thang.chargeops.booking.checkin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class CheckInChallengeRepository {

    private static final String KEY_PREFIX = "checkin:challenge:";
    private static final String CONNECTOR_KEY_PREFIX = "checkin:connector:";
    private static final long MIN_REMAINING_TTL_SECONDS = 5L;

    /**
     * Lua script for atomic Get-Or-Create of check-in challenge (Phương án A - Reuse challenge trong TTL).
     * KEYS[1]: checkin:connector:{connectorId}
     * KEYS[2]: prefix checkin:challenge:
     * ARGV[1]: connectorId
     * ARGV[2]: fallback/generatedToken
     * ARGV[3]: ttlSeconds (e.g. 60)
     * ARGV[4]: minRemainingTtlSeconds (e.g. 5)
     *
     * Returns:
     *   {token, remainingTtlSeconds}
     */
    private static final String GET_OR_CREATE_LUA =
            "local existingToken = redis.call('get', KEYS[1]) " +
            "if existingToken then " +
            "    local ttl = redis.call('ttl', KEYS[1]) " +
            "    if ttl and ttl > tonumber(ARGV[4]) then " +
            "        local existingConn = redis.call('get', KEYS[2] .. existingToken) " +
            "        if existingConn == ARGV[1] then " +
            "            return {existingToken, tostring(ttl)} " +
            "        end " +
            "    end " +
            "end " +
            "redis.call('set', KEYS[1], ARGV[2], 'EX', tonumber(ARGV[3])) " +
            "redis.call('set', KEYS[2] .. ARGV[2], ARGV[1], 'EX', tonumber(ARGV[3])) " +
            "return {ARGV[2], ARGV[3]}";
    private static final RedisScript<List> GET_OR_CREATE_SCRIPT =
            new DefaultRedisScript<>(GET_OR_CREATE_LUA, List.class);

    /**
     * Lua script for atomic Compare-And-Delete (CAD).
     * Deletes both checkin:challenge:{token} and checkin:connector:{expectedConnectorId} if matched.
     * Returns:
     *   1: key exists and value matches expected -> both keys deleted
     *   0: key exists but value != expected -> keys are NOT deleted
     *  -1: key does not exist or has expired
     */
    private static final String CAD_LUA =
            "local val = redis.call('get', KEYS[1]) " +
            "if not val then return -1 end " +
            "if val == ARGV[1] then " +
            "    redis.call('del', KEYS[1]) " +
            "    redis.call('del', KEYS[2]) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";
    private static final RedisScript<Long> COMPARE_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>(CAD_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;

    public IssuedCheckInChallenge getOrCreate(
            UUID connectorId,
            String fallbackToken,
            Duration ttl
    ) {
        @SuppressWarnings("unchecked")
        List<String> result = redisTemplate.execute(
                GET_OR_CREATE_SCRIPT,
                List.of(connectorKey(connectorId), KEY_PREFIX),
                connectorId.toString(),
                fallbackToken,
                String.valueOf(ttl.toSeconds()),
                String.valueOf(MIN_REMAINING_TTL_SECONDS)
        );
        if (result != null && result.size() >= 2) {
            String token = result.get(0);
            try {
                long remaining = Long.parseLong(result.get(1));
                return new IssuedCheckInChallenge(token, remaining);
            } catch (NumberFormatException ignored) {
                return new IssuedCheckInChallenge(token, ttl.toSeconds());
            }
        }
        save(fallbackToken, connectorId, ttl);
        return new IssuedCheckInChallenge(fallbackToken, ttl.toSeconds());
    }

    public void save(
            String token,
            UUID connectorId,
            Duration ttl
    ) {
        redisTemplate.opsForValue().set(key(token), connectorId.toString(), ttl);
        redisTemplate.opsForValue().set(connectorKey(connectorId), token, ttl);
    }

    public Optional<UUID> findConnectorId(String token) {
        String connectorId = redisTemplate.opsForValue().get(key(token));
        return Optional.ofNullable(connectorId)
                .map(UUID::fromString);
    }

    /**
     * Atomically compares the token's associated connectorId with expectedConnectorId.
     * Deletes both the challenge token and connector mapping ONLY IF they match.
     */
    public boolean compareAndDelete(String token, UUID expectedConnectorId) {
        if (token == null || token.isBlank() || expectedConnectorId == null) {
            return false;
        }
        Long result = redisTemplate.execute(
                COMPARE_AND_DELETE_SCRIPT,
                List.of(key(token), connectorKey(expectedConnectorId)),
                expectedConnectorId.toString()
        );
        return result != null && result == 1L;
    }

    /**
     * Retrieves the remaining TTL in seconds for a given challenge token.
     */
    public Optional<Long> getRemainingTtlSeconds(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Long ttl = redisTemplate.getExpire(key(token), TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0) {
            return Optional.empty();
        }
        return Optional.of(ttl);
    }

    /**
     * Atomically retrieves and deletes the challenge token in a single Redis command (GETDEL).
     * Also clears connector mapping.
     */
    public Optional<UUID> getAndDelete(String token) {
        String connectorId = redisTemplate.opsForValue().getAndDelete(key(token));
        if (connectorId != null) {
            try {
                UUID connId = UUID.fromString(connectorId);
                redisTemplate.delete(connectorKey(connId));
                return Optional.of(connId);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Optional.empty();
    }

    public void delete(String token) {
        String connectorId = redisTemplate.opsForValue().getAndDelete(key(token));
        if (connectorId != null) {
            try {
                redisTemplate.delete(connectorKey(UUID.fromString(connectorId)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }

    private String connectorKey(UUID connectorId) {
        return CONNECTOR_KEY_PREFIX + connectorId;
    }
}
