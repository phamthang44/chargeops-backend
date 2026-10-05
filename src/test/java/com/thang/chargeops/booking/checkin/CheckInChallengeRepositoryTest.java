package com.thang.chargeops.booking.checkin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckInChallengeRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CheckInChallengeRepository repository;

    private final UUID connectorId = UUID.randomUUID();
    private final String token = "test-challenge-token-123456";

    @Test
    void compareAndDelete_returnsTrueWhenMatched() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("checkin:challenge:" + token)), eq(connectorId.toString())))
                .thenReturn(1L);

        boolean result = repository.compareAndDelete(token, connectorId);

        assertThat(result).isTrue();
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = redisScriptCaptor();
        verify(redisTemplate).execute(scriptCaptor.capture(), eq(List.of("checkin:challenge:" + token)), eq(connectorId.toString()));
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("redis.call('get', KEYS[1])")
                .contains("if val == ARGV[1]")
                .contains("redis.call('del', KEYS[1])");
    }

    @Test
    void compareAndDelete_returnsFalseWhenMismatch() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("checkin:challenge:" + token)), eq(connectorId.toString())))
                .thenReturn(0L);

        boolean result = repository.compareAndDelete(token, connectorId);

        assertThat(result).isFalse();
    }

    @Test
    void compareAndDelete_returnsFalseWhenNotFoundOrExpired() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("checkin:challenge:" + token)), eq(connectorId.toString())))
                .thenReturn(-1L);

        boolean result = repository.compareAndDelete(token, connectorId);

        assertThat(result).isFalse();
    }

    @Test
    void compareAndDelete_returnsFalseOnNullOrBlankInput() {
        assertThat(repository.compareAndDelete(null, connectorId)).isFalse();
        assertThat(repository.compareAndDelete("   ", connectorId)).isFalse();
        assertThat(repository.compareAndDelete(token, null)).isFalse();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void save_setsKeyWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.save(token, connectorId, Duration.ofSeconds(60));

        verify(valueOperations).set("checkin:challenge:" + token, connectorId.toString(), Duration.ofSeconds(60));
    }

    @Test
    void findConnectorId_returnsParsedUuid() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("checkin:challenge:" + token)).thenReturn(connectorId.toString());

        Optional<UUID> resolved = repository.findConnectorId(token);

        assertThat(resolved).contains(connectorId);
    }

    @Test
    void getRemainingTtlSeconds_returnsTtl() {
        when(redisTemplate.getExpire("checkin:challenge:" + token, TimeUnit.SECONDS)).thenReturn(45L);

        Optional<Long> ttl = repository.getRemainingTtlSeconds(token);

        assertThat(ttl).contains(45L);
    }

    @Test
    void getRemainingTtlSeconds_returnsEmptyWhenKeyNotFound() {
        when(redisTemplate.getExpire("checkin:challenge:" + token, TimeUnit.SECONDS)).thenReturn(-2L);

        Optional<Long> ttl = repository.getRemainingTtlSeconds(token);

        assertThat(ttl).isEmpty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<RedisScript<Long>> redisScriptCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(RedisScript.class);
    }
}
