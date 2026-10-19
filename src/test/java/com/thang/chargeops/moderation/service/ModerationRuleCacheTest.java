package com.thang.chargeops.moderation.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ModerationErrorCode;
import com.thang.chargeops.moderation.model.*;
import com.thang.chargeops.moderation.service.impl.ContentModerationServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModerationRuleCacheTest {
    private final ModerationRuleLoader loader = mock(ModerationRuleLoader.class);
    private final AtomicLong nanos = new AtomicLong();
    private final ModerationRuleCache cache = new ModerationRuleCache(loader, nanos::get);
    private final ModerationRule rule = new ModerationRule(UUID.randomUUID(), "bad", "bad", TermMatchType.WORD_OR_PHRASE,
            Set.of(ContentScope.DISPLAY_NAME, ContentScope.TICKET_MESSAGE));

    @Test void twoInstancesRefreshAtFiveSecondsAndLocalInvalidationIsImmediate() {
        when(loader.load()).thenReturn(List.of());
        var other = new ModerationRuleCache(loader, nanos::get);
        assertThat(cache.rules()).isEmpty();
        assertThat(other.rules()).isEmpty();
        when(loader.load()).thenReturn(List.of(rule));
        nanos.set(TimeUnit.SECONDS.toNanos(5) - 1);
        assertThat(cache.rules()).isEmpty();
        cache.invalidate();
        assertThat(cache.rules()).containsExactly(rule);
        assertThat(other.rules()).isEmpty();
        nanos.incrementAndGet();
        assertThat(other.rules()).containsExactly(rule);
        verify(loader, times(4)).load();
    }
    @Test void expiredFailureIsUnavailableAndCanRecover() {
        when(loader.load()).thenReturn(List.of(rule));
        cache.rules();
        nanos.set(TimeUnit.SECONDS.toNanos(5));
        when(loader.load()).thenThrow(new IllegalStateException("secret storage details"));
        assertThatThrownBy(cache::rules).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ModerationErrorCode.MODERATION_UNAVAILABLE));
        doReturn(List.of()).when(loader).load();
        assertThat(cache.rules()).isEmpty();
    }
    @Test void slowLoadCannotExtendThePolicyLifetime() {
        when(loader.load()).thenAnswer(invocation -> { nanos.addAndGet(TimeUnit.SECONDS.toNanos(5)); return List.of(rule); });
        assertThatThrownBy(cache::rules).isInstanceOf(AppException.class);
    }
    @Test void concurrentReadersUseOneImmutableLoad() throws Exception {
        when(loader.load()).thenReturn(new ArrayList<>(List.of(rule)));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<List<ModerationRule>>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(cache::rules));
            for (var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS)).containsExactly(rule);
        }
        verify(loader).load();
        assertThatThrownBy(() -> cache.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void invalidationWaitsForInFlightLoadAndPreventsStaleReuse() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(loader.load()).thenAnswer(invocation -> {
            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException();
            return List.of();
        }).thenReturn(List.of(rule));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var read = executor.submit(cache::rules);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var invalidation = executor.submit(cache::invalidate);
            finish.countDown();
            read.get(5, TimeUnit.SECONDS);
            invalidation.get(5, TimeUnit.SECONDS);
            assertThat(cache.rules()).containsExactly(rule);
        }
    }
    @Test void fieldErrorsDoNotLeakRulesAndSsoUsesFallback() {
        when(loader.load()).thenReturn(List.of(rule));
        var moderation = new ContentModerationServiceImpl(cache);
        assertThatThrownBy(() -> moderation.validate(List.of(
                new ContentModerationService.Field("displayName", ContentScope.DISPLAY_NAME, "BAD"),
                new ContentModerationService.Field("body", ContentScope.TICKET_MESSAGE, "quoted bad"))))
                .isInstanceOfSatisfying(AppException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ModerationErrorCode.CONTENT_PROHIBITED);
                    assertThat(error.getDetails()).isEqualTo(Map.of("fields", List.of("displayName", "body")));
                    assertThat(error.getMessage()).doesNotContain("BAD");
                });
        assertThat(moderation.safeImportedDisplayName("bad")).isEqualTo("Người dùng");
        assertThat(moderation.safeImportedDisplayName("Nguyễn Văn An")).isEqualTo("Nguyễn Văn An");
        assertThat(moderation.check(ContentScope.TICKET_MESSAGE, "bad").matches()).hasSize(1);
    }
}
