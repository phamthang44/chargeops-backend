package com.thang.chargeops.infra.security.abuse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AbuseRateLimiterTest {
    @Test void bindsDocumentedRateOverridesAndMode() {
        var values = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "app.abuse.mode", "ENFORCE", "app.abuse.limits.BOOKING_CREATE", "8"));
        var properties = new org.springframework.boot.context.properties.bind.Binder(values).bind("app.abuse",
                org.springframework.boot.context.properties.bind.Bindable.of(AbuseProperties.class)).get();
        assertThat(properties.getMode()).isEqualTo(AbuseProperties.Mode.ENFORCE);
        assertThat(properties.getLimits().get(AbuseGroup.BOOKING_CREATE)).isEqualTo(8);
    }
    private AbuseProperties config(AbuseProperties.Mode mode) {
        var p = new AbuseProperties(); p.setMode(mode); p.setHmacKey("test-only-hmac-material-at-least-32-bytes"); return p;
    }
    @Test void enforceRejectsMissingKey() {
        var p = new AbuseProperties(); p.setMode(AbuseProperties.Mode.ENFORCE);
        assertThatThrownBy(() -> new AbuseRateLimiter(mock(StringRedisTemplate.class), p, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void observeRecordsStoreFailureButNeverBlocks() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("secret"));
        var metrics = new SimpleMeterRegistry();
        var limiter = new AbuseRateLimiter(redis, config(AbuseProperties.Mode.OBSERVE), metrics);
        assertThat(limiter.acquire(AbuseGroup.EXPORT, "subject", "192.0.2.1").code()).isEqualTo("SEC_003");
        assertThat(metrics.get("chargeops.abuse.decisions").tag("decision", "SEC_003").counter().count()).isEqualTo(1);
    }
    @Test void enforceFailsClosedOnlyWhenLimiterIsAskedToProtectAnAction() {
        var limiter = new AbuseRateLimiter(mock(StringRedisTemplate.class), config(AbuseProperties.Mode.ENFORCE), new SimpleMeterRegistry());
        assertThat(limiter.acquire(AbuseGroup.BOOKING_CREATE, "s", "192.0.2.1"))
                .isEqualTo(new AbuseRateLimiter.Decision("SEC_003", 5, "store"));
    }
    @Test void translatesAtomicCooldownAndDoesNotPutIdentityInMetricLabels() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(2L, 899L, 1L));
        var metrics = new SimpleMeterRegistry();
        var limiter = new AbuseRateLimiter(redis, config(AbuseProperties.Mode.ENFORCE), metrics);
        assertThat(limiter.acquire(AbuseGroup.EXPORT, "private-subject", "192.0.2.9"))
                .isEqualTo(new AbuseRateLimiter.Decision("SEC_002", 899, "ip"));
        assertThat(metrics.getMeters().getFirst().getId().getTags().toString()).doesNotContain("192.0.2.9", "private-subject");
        assertThat(limiter.fingerprint("ip:192.0.2.9")).hasSize(64).isEqualTo(limiter.fingerprint("ip:192.0.2.9"));
    }
    @Test void offNeverTouchesRedis() {
        var redis = mock(StringRedisTemplate.class);
        var limiter = new AbuseRateLimiter(redis, config(AbuseProperties.Mode.OFF), new SimpleMeterRegistry());
        assertThat(limiter.acquire(AbuseGroup.EXPORT, "s", "192.0.2.1").allowed()).isTrue();
        verifyNoInteractions(redis);
    }
}
