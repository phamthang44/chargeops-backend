package com.thang.chargeops.infra.persistence;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the internal profile ID responsible for writes during the current
 * request. The value is thread-bound because Spring MVC handles a synchronous
 * request on one thread.
 */
@Component
public class CurrentActorContext {

    private final ThreadLocal<UUID> actorId = new ThreadLocal<>();

    public void set(UUID profileId) {
        actorId.set(profileId);
    }

    public Optional<UUID> get() {
        return Optional.ofNullable(actorId.get());
    }

    public void clear() {
        actorId.remove();
    }
}
