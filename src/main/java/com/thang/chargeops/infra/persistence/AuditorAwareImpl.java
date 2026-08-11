package com.thang.chargeops.infra.persistence;

import org.springframework.data.domain.AuditorAware;

import java.util.Optional;
import java.util.UUID;

public class AuditorAwareImpl implements AuditorAware<UUID> {

    private final CurrentActorContext currentActorContext;

    public AuditorAwareImpl(CurrentActorContext currentActorContext) {
        this.currentActorContext = currentActorContext;
    }

    @Override
    public Optional<UUID> getCurrentAuditor() {
        // Never query JPA here: this method runs while Hibernate is flushing.
        return currentActorContext.get();
    }
}
