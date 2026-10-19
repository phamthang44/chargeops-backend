package com.thang.chargeops.moderation.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ModerationErrorCode;
import com.thang.chargeops.moderation.model.ModerationRule;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ModerationRuleCache {
    private static final long TTL = TimeUnit.SECONDS.toNanos(5);
    private final ModerationRuleLoader loader;
    private final LongSupplier ticker;
    private List<ModerationRule> rules;
    private long loadedSince;

    @Autowired
    public ModerationRuleCache(ModerationRuleLoader loader) { this(loader, System::nanoTime); }

    ModerationRuleCache(ModerationRuleLoader loader, LongSupplier ticker) {
        this.loader = loader;
        this.ticker = ticker;
    }

    /** Serialize load and invalidation so an older load cannot replace an invalidated snapshot. */
    public synchronized List<ModerationRule> rules() {
        long now = ticker.getAsLong();
        if (rules != null && now - loadedSince < TTL) return rules;
        try {
            List<ModerationRule> loaded = List.copyOf(loader.load());
            // TTL starts BEFORE the DB read, so slow reads do not extend stale policy lifetime.
            if (ticker.getAsLong() - now >= TTL) {
                throw new AppException(ModerationErrorCode.MODERATION_UNAVAILABLE);
            }
            rules = loaded;
            loadedSince = now;
            return rules;
        } catch (RuntimeException exception) {
            rules = null;
            throw new AppException(ModerationErrorCode.MODERATION_UNAVAILABLE);
        }
    }

    public synchronized void invalidate() { rules = null; }
}
