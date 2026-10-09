package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, per-instance limiter for the single-backend dev/demo deployment. */
@Component
public class AssistantRateLimiter {
    private static final int MAX_TRACKED_USERS = 10000;
    private static final ZoneId DAILY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final AssistantProperties properties;
    private final Clock clock;
    private final Map<UUID, Window> windows = new HashMap<>();
    private long currentDay = Long.MIN_VALUE;
    private int totalCount;

    @Autowired
    public AssistantRateLimiter(AssistantProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AssistantRateLimiter(AssistantProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized void acquire(UUID user) {
        long now = clock.millis();
        long minute = Math.floorDiv(now, 60000);
        var date = Instant.ofEpochMilli(now).atZone(DAILY_ZONE).toLocalDate();
        long day = date.toEpochDay();
        long dailyReset = date.plusDays(1).atStartOfDay(DAILY_ZONE).toInstant().toEpochMilli();
        if (currentDay != day) {
            windows.clear();
            totalCount = 0;
            currentDay = day;
        }
        Window window = windows.get(user);
        // Reserve all counters atomically before the paid call. Rejections spend no quota.
        if (totalCount >= properties.getTotalRequestsPerDay()) {
            throw limited(AssistantErrorCode.QUOTA_EXCEEDED, "INSTANCE_DAY", properties.getTotalRequestsPerDay(), now, dailyReset);
        }
        if (window != null && window.dayCount >= properties.getRequestsPerDay()) {
            throw limited(AssistantErrorCode.QUOTA_EXCEEDED, "OWNER_DAY", properties.getRequestsPerDay(), now, dailyReset);
        }
        if (window == null) {
            if (windows.size() >= MAX_TRACKED_USERS) {
                throw limited(AssistantErrorCode.QUOTA_EXCEEDED, "CAPACITY", MAX_TRACKED_USERS, now, dailyReset);
            }
            window = new Window(minute);
            windows.put(user, window);
        }
        if (window.minute != minute) {
            window.minute = minute;
            window.count = 0;
        }
        if (window.count >= properties.getRequestsPerMinute()) {
            throw limited(AssistantErrorCode.RATE_LIMITED, "OWNER_MINUTE", properties.getRequestsPerMinute(), now, (minute + 1) * 60000);
        }
        window.count++;
        window.dayCount++;
        totalCount++;
    }

    private static AppException limited(AssistantErrorCode code, String scope, int limit, long now, long reset) {
        return AppException.withDetails(code, Map.of("scope", scope, "limit", limit,
                "retryAfterSeconds", Math.max(1, (reset - now + 999) / 1000),
                "resetAt", Instant.ofEpochMilli(reset).toString()));
    }

    private static class Window {
        private long minute;
        private int count;
        private int dayCount;
        private Window(long minute) { this.minute = minute; }
    }
}
