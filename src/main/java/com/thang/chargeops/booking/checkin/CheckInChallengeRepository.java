package com.thang.chargeops.booking.checkin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CheckInChallengeRepository {

    private static final String KEY_PREFIX = "checkin:challenge:";
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
