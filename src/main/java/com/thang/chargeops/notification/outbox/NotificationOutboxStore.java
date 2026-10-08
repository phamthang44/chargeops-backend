package com.thang.chargeops.notification.outbox;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Short, independently committed transactions. Database time owns leases and retry deadlines. */
@Repository
public class NotificationOutboxStore {
    private final NotificationOutboxRepository repository;

    public NotificationOutboxStore(NotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNext(int leaseSeconds) {
        if (leaseSeconds < 120) throw new IllegalArgumentException("Outbox lease must be at least 120 seconds");
        var token = UUID.randomUUID();
        return repository.claimNext(token, leaseSeconds).stream().findFirst()
                .map(row -> new Claim(row.getEventId(), row.getToken(), row.getPayload(), row.getAttemptCount()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(Claim claim) {
        return repository.markPublished(claim.eventId(), claim.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryLater(Claim claim, String failureCode) {
        // Store a controlled code only; exception messages may contain broker credentials or payload.
        if (!java.util.Set.of("KAFKA_ACK_TIMEOUT", "KAFKA_PUBLISH_FAILED", "INVALID_EVENT").contains(failureCode)) {
            throw new IllegalArgumentException("Unknown outbox failure code");
        }
        return repository.retryLater(claim.eventId(), claim.token(), failureCode) == 1;
    }

    public record Claim(UUID eventId, UUID token, String payload, int attemptCount) {}
}
