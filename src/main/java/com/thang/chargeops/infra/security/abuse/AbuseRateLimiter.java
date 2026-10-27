package com.thang.chargeops.infra.security.abuse;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

@Component
public class AbuseRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(AbuseRateLimiter.class);
    private final StringRedisTemplate redis;
    private final AbuseProperties properties;
    private final MeterRegistry metrics;
    private final byte[] key;
    private final java.util.concurrent.atomic.AtomicLong lastCooldown = new java.util.concurrent.atomic.AtomicLong();
    private final DefaultRedisScript<List> script;

    public record Decision(String code, long retryAfterSeconds, String scope) {
        public static Decision allow() { return new Decision(null, 0, "none"); }
        public boolean allowed() { return code == null; }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AbuseRateLimiter(AbuseRedisStore store, AbuseProperties properties, MeterRegistry metrics) {
        this(store.template(), properties, metrics);
    }
    AbuseRateLimiter(StringRedisTemplate redis, AbuseProperties properties, MeterRegistry metrics) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
        metrics.gauge("chargeops.abuse.last.cooldown", java.util.List.of(io.micrometer.core.instrument.Tag.of("mode", properties.getMode().name())), lastCooldown);
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/abuse-token-bucket.lua"));
        script.setResultType(List.class);
        if (properties.getStrikeWindowSeconds() < 60 || properties.getCooldownSeconds() < 1 ||
                properties.getStrikeMinutes() < 1) throw new IllegalArgumentException("Invalid abuse time configuration");
        if (properties.getLimits() == null || properties.getLimits().values().stream().anyMatch(v -> v == null || v < 1 || v > 10000))
            throw new IllegalArgumentException("Abuse limits must be between 1 and 10000 per minute");
        if (properties.getHmacKey().isBlank()) {
            if (properties.getMode() == AbuseProperties.Mode.ENFORCE)
                throw new IllegalStateException("ABUSE_HMAC_KEY is required in ENFORCE mode");
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = properties.getHmacKey().getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) throw new IllegalArgumentException("ABUSE_HMAC_KEY must contain at least 32 bytes");
        }
    }

    public AbuseProperties.Mode mode() { return properties.getMode(); }

    public Decision acquire(AbuseGroup group, String subject, String ip) {
        if (mode() == AbuseProperties.Mode.OFF) return Decision.allow();
        String actor = fingerprint("subject:" + subject);
        String address = ip == null ? null : fingerprint("ip:" + ip);
        // Hash-tag keeps the six keys in one Redis cluster slot. Namespace isolates observe state.
        String prefix = "abuse:v1:" + mode() + ":{" + group + "}:";
        String ipKey = prefix + "ip:" + (address == null ? actor : address);
        String userKey = prefix + "subject:" + actor;
        Decision decision;
        try {
            List<?> result = redis.execute(script, List.of(ipKey, ipKey + ":strikes", ipKey + ":cooldown",
                    userKey, userKey + ":strikes", userKey + ":cooldown"),
                    Integer.toString(properties.getLimits().getOrDefault(group, group.perMinute)), address == null ? "0" : "10",
                    Integer.toString(properties.getStrikeWindowSeconds()),
                    Integer.toString(properties.getCooldownSeconds()), Integer.toString(properties.getStrikeMinutes()));
            if (result == null || result.size() != 3) throw new IllegalStateException("Invalid limiter response");
            int code = ((Number) result.get(0)).intValue();
            if (code < 0 || code > 2) throw new IllegalStateException("Invalid limiter decision");
            decision = code == 0 ? Decision.allow() : new Decision("SEC_00" + code,
                    Math.max(1, ((Number) result.get(1)).longValue()),
                    ((Number) result.get(2)).intValue() == 1 ? "ip" : "subject");
        } catch (RuntimeException failure) {
            decision = new Decision("SEC_003", 5, "store");
        }
        metrics.counter("chargeops.abuse.decisions", "group", group.name(), "decision",
                decision.allowed() ? "allowed" : decision.code(), "mode", mode().name()).increment();
        if (!decision.allowed()) {
            if ("SEC_002".equals(decision.code())) lastCooldown.set(System.currentTimeMillis() / 1000);
            // Stable pseudonyms only; no raw IP, JWT, subject, URL, body or exception text.
            log.warn("security_abuse group={} decision={} scope={} actor={} address={} retry={} mode={}",
                    group, decision.code(), decision.scope(), actor, address, decision.retryAfterSeconds(), mode());
        }
        return decision;
    }

    public String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("HMAC unavailable", impossible); }
    }
    public void sessionCapacityReached(String subject) {
        metrics.counter("chargeops.abuse.decisions", "group", "WS_SESSION_CAP", "decision", "SEC_001", "mode", mode().name()).increment();
        log.warn("security_abuse group=WS_SESSION_CAP decision=SEC_001 scope=subject actor={} mode={}", fingerprint("subject:" + subject), mode());
    }
}
