package com.thang.chargeops.booking.checkin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class CheckInChallengeRepository {

    private static final String KEY_PREFIX = "checkin:challenge:";

    /**
     * Lua script for atomic Compare-And-Delete (CAD).
     * Returns:
     *   1: key exists and value matches expected -> key is deleted
     *   0: key exists but value != expected -> key is NOT deleted
     *  -1: key does not exist or has expired
     */
    private static final String CAD_LUA =
            "local val = redis.call('get', KEYS[1]) " +
            "if not val then return -1 end " +
            "if val == ARGV[1] then " +
            "    redis.call('del', KEYS[1]) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";
    private static final RedisScript<Long> COMPARE_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>(CAD_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;

    public void save(
            String token,
            UUID connectorId,
            Duration ttl
    ) {
        // SET key value EX ttl
        redisTemplate.opsForValue().set(key(token), connectorId.toString(), ttl);
    }

    public Optional<UUID> findConnectorId(String token) {
        // GET key token
        String connectorId = redisTemplate.opsForValue().get(key(token));

        return Optional.ofNullable(connectorId)
                .map(UUID::fromString);
    }

    /**
     * Atomically compares the token's associated connectorId with expectedConnectorId.
     * Deletes the key ONLY IF they match.
     * If they do not match, the key is preserved so that the correct connector's check-in is not invalidated.
     *
     * @return true if token existed and matched expectedConnectorId and was successfully deleted;
     *         false if token did not exist, was expired, or belonged to a different connector.
     */
    public boolean compareAndDelete(String token, UUID expectedConnectorId) {
        if (token == null || token.isBlank() || expectedConnectorId == null) {
            return false;
        }
        Long result = redisTemplate.execute(
                COMPARE_AND_DELETE_SCRIPT,
                Collections.singletonList(key(token)),
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
     * Guarantees strict single-use semantics even under highly concurrent requests.
     */
    public Optional<UUID> getAndDelete(String token) {
        String connectorId = redisTemplate.opsForValue().getAndDelete(key(token));
        return Optional.ofNullable(connectorId)
                .map(UUID::fromString);
    }

    public void delete(String token) {
        // DEL key
        redisTemplate.delete(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
