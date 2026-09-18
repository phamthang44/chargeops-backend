package com.thang.chargeops.booking.command;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed distributed mutex for command operations.
 * Acts as an in-flight fast-fail concurrency gatekeeper to prevent duplicate
 * external gateway requests or concurrent execution on double-click before durable
 * persistence in PostgreSQL takes place.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCommandInFlightLock {

    private static final String LOCK_PREFIX = "lock:command:";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(10);

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) " +
            "else " +
            "return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;

    public <T> T executeWithLock(
            UUID actorId,
            BookingCommandOperation operation,
            UUID requestKey,
            Supplier<T> action
    ) {
        return executeWithLock(actorId, operation, requestKey, DEFAULT_TTL, action);
    }

    public <T> T executeWithLock(
            UUID actorId,
            BookingCommandOperation operation,
            UUID requestKey,
            Duration ttl,
            Supplier<T> action
    ) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");

        String key = buildKey(actorId, operation, requestKey);
        String lockToken = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, lockToken, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("Concurrent command execution rejected (in-flight). Key: {}, Actor: {}, Operation: {}",
                    key, actorId, operation);
            throw new AppException(CommandErrorCode.IN_PROGRESS);
        }

        try {
            return action.get();
        } finally {
            releaseLock(key, lockToken);
        }
    }

    private void releaseLock(String key, String lockToken) {
        try {
            redisTemplate.execute(
                    new DefaultRedisScript<>(UNLOCK_LUA, Long.class),
                    Collections.singletonList(key),
                    lockToken
            );
        } catch (Exception ex) {
            log.warn("Failed to safely release in-flight lock key: {}", key, ex);
        }
    }

    private String buildKey(UUID actorId, BookingCommandOperation operation, UUID requestKey) {
        return LOCK_PREFIX + operation.name() + ":" + actorId + ":" + requestKey;
    }
}
