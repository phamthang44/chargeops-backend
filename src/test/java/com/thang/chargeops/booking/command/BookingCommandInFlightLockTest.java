package com.thang.chargeops.booking.command;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingCommandInFlightLockTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private BookingCommandInFlightLock lock;

    private final UUID actorId = UUID.randomUUID();
    private final BookingCommandOperation operation = BookingCommandOperation.CREATE_CHECKOUT;
    private final UUID requestKey = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lock = new BookingCommandInFlightLock(redisTemplate);
    }

    @Test
    @DisplayName("Successfully acquires lock, executes action, and safely releases lock with token")
    void executeWithLock_Success_ExecutesActionAndReleasesLock() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        String result = lock.executeWithLock(actorId, operation, requestKey, () -> "OK");

        assertThat(result).isEqualTo("OK");

        String expectedKey = "lock:command:CREATE_CHECKOUT:" + actorId + ":" + requestKey;
        verify(valueOperations).setIfAbsent(eq(expectedKey), anyString(), eq(Duration.ofSeconds(10)));
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(expectedKey)), anyString());
    }

    @Test
    @DisplayName("Throws CMD_IN_PROGRESS (HTTP 409) when lock already held by concurrent request")
    void executeWithLock_WhenLockNotAcquired_ThrowsAppExceptionInProgress() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        boolean[] actionExecuted = {false};

        assertThatThrownBy(() -> lock.executeWithLock(actorId, operation, requestKey, () -> {
            actionExecuted[0] = true;
            return "SHOULD_NOT_REACH";
        }))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(CommandErrorCode.IN_PROGRESS);

        assertThat(actionExecuted[0]).isFalse();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("Safely releases lock even when action throws exception")
    void executeWithLock_WhenActionThrows_ReleasesLockSafely() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        String expectedKey = "lock:command:CREATE_CHECKOUT:" + actorId + ":" + requestKey;

        assertThatThrownBy(() -> lock.executeWithLock(actorId, operation, requestKey, () -> {
            throw new IllegalStateException("Gateway timeout simulation");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Gateway timeout simulation");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(expectedKey)), anyString());
    }

    @Test
    @DisplayName("Exception during lock release is handled gracefully without masking action return")
    void executeWithLock_WhenRedisReleaseFails_DoesNotBreakSuccessfulAction() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RuntimeException("Redis connection blip"));

        String result = lock.executeWithLock(actorId, operation, requestKey, () -> "VALUE");

        assertThat(result).isEqualTo("VALUE");
    }
}
