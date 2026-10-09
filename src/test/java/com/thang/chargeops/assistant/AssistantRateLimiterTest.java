package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.AppException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AssistantRateLimiterTest {
    @Test
    void minuteBoundaryResetsLimitForTheSameActor() {
        AssistantProperties properties = new AssistantProperties();
        properties.setRequestsPerMinute(1);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(59999L);
        AssistantRateLimiter limiter = new AssistantRateLimiter(properties, clock);
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOf(AppException.class);
        when(clock.millis()).thenReturn(60000L);
        assertThatCode(() -> limiter.acquire(user)).doesNotThrowAnyException();
    }

    @Test
    void boundedUserCapacityRejectsInsteadOfGrowingForever() {
        AssistantProperties properties = new AssistantProperties();
        properties.setTotalRequestsPerDay(100000);
        AssistantRateLimiter limiter = new AssistantRateLimiter(properties,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 10000; i++) { limiter.acquire(new UUID(0, i)); }
        assertThatThrownBy(() -> limiter.acquire(new UUID(0, 10000))).isInstanceOf(AppException.class);
    }

    @Test
    void minuteResetDoesNotResetDailyQuotaAndMidnightVietnamDoes() {
        AssistantProperties properties = new AssistantProperties();
        properties.setRequestsPerDay(1);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(Instant.parse("2026-10-08T16:58:00Z").toEpochMilli());
        AssistantRateLimiter limiter = new AssistantRateLimiter(properties, clock);
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        when(clock.millis()).thenReturn(Instant.parse("2026-10-08T16:59:59Z").toEpochMilli());
        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOfSatisfying(AppException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.QUOTA_EXCEEDED);
            var details = (java.util.Map<?, ?>) error.getDetails();
            assertThat(details.get("scope")).isEqualTo("OWNER_DAY");
            assertThat(details.get("resetAt")).isEqualTo("2026-10-08T17:00:00Z");
            assertThat(details.get("retryAfterSeconds")).isEqualTo(1L);
        });
        when(clock.millis()).thenReturn(Instant.parse("2026-10-08T17:00:00Z").toEpochMilli());
        assertThatCode(() -> limiter.acquire(user)).doesNotThrowAnyException();
    }

    @Test
    void ownerRejectionDoesNotSpendOtherOwnersInstanceAllowance() {
        AssistantProperties properties = new AssistantProperties();
        properties.setRequestsPerDay(1);
        properties.setTotalRequestsPerDay(2);
        AssistantRateLimiter limiter = new AssistantRateLimiter(properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID first = UUID.randomUUID();
        limiter.acquire(first);
        assertThatThrownBy(() -> limiter.acquire(first)).isInstanceOf(AppException.class);
        assertThatCode(() -> limiter.acquire(UUID.randomUUID())).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(UUID.randomUUID())).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(((java.util.Map<?, ?>) error.getDetails()).get("scope")).isEqualTo("INSTANCE_DAY"));
    }

    @Test
    void minuteRejectionDoesNotSpendDailyAllowance() {
        AssistantProperties properties = new AssistantProperties();
        properties.setRequestsPerMinute(1);
        properties.setRequestsPerDay(2);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        AssistantRateLimiter limiter = new AssistantRateLimiter(properties, clock);
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.RATE_LIMITED));
        when(clock.millis()).thenReturn(60000L);
        assertThatCode(() -> limiter.acquire(user)).doesNotThrowAnyException();
    }

    @Test
    void simultaneousOwnersCannotOverrunInstanceQuota() throws Exception {
        AssistantProperties properties = new AssistantProperties();
        properties.setTotalRequestsPerDay(5);
        AssistantRateLimiter limiter = new AssistantRateLimiter(properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            java.util.List<java.util.concurrent.Callable<Boolean>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) { calls.add(() -> {
                try { limiter.acquire(UUID.randomUUID()); return true; }
                catch (AppException error) {
                    assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.QUOTA_EXCEEDED);
                    return false;
                }
            }); }
            int admitted = 0;
            for (var result : pool.invokeAll(calls)) { if (result.get()) { admitted++; } }
            assertThat(admitted).isEqualTo(5);
        }
    }
}
