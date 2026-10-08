package com.thang.chargeops.notification.email;

import com.thang.chargeops.notification.kafka.BookingNotificationEvent;
import com.thang.chargeops.notification.kafka.InvalidBookingNotificationException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import com.thang.chargeops.notification.model.NotificationEventType;
import com.thang.chargeops.notification.outbox.NotificationOutboxRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.Optional;

/** Durable ingestion only. Resolving an email address and sending SMTP belong to the delivery worker. */
@Service
@RequiredArgsConstructor
public class NotificationEmailDeliveryStore {
    private final NotificationEmailDeliveryRepository repository;
    private final ObjectMapper json;
    private final NotificationOutboxRepository outbox;
    private final BookingEventEmailSource eventSources;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean captureEvent(BookingNotificationEvent event) {
        var intent = outbox.findById(event.eventId()).orElseThrow(() -> new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE"));
        if (!json.readTree(intent.getPayload()).equals(json.valueToTree(event))) throw new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE");
        var source = eventSources.resolve(intent, "http://localhost", "");
        return repository.insertIfAbsent(UUID.randomUUID(), event.eventId(), event.recipientId(),
                source.skipReason() == null ? "PENDING" : "SKIPPED", source.skipReason()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean captureCancelled(BookingNotificationEvent event) {
        var intent = outbox.findById(event.eventId())
                .orElseThrow(() -> new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE"));
        if (event.eventType() != NotificationEventType.BOOKING_CANCELLED
                || !json.readTree(intent.getPayload()).equals(json.valueToTree(event))) {
            throw new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE");
        }
        var source = repository.findCancelledContext(event.eventId())
                .orElseThrow(() -> new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE"));
        String reason = skipReason(source);
        return repository.insertIfAbsent(UUID.randomUUID(), event.eventId(), event.recipientId(),
                reason == null ? "PENDING" : "SKIPPED", reason) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean captureConfirmed(BookingNotificationEvent event) {
        // The consumer runs in this backend/database: accept only the persisted authoritative intent.
        // PROCESSING/PENDING are valid too: Kafka can deliver before the publisher writes PUBLISHED.
        var source = repository.findConfirmedSource(event.eventId(), json.writeValueAsString(event))
                .orElseThrow(() -> new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE"));
        String skipReason = !source.getActive() ? "RECIPIENT_INACTIVE"
                : !source.getRecipientScope() ? "RECIPIENT_SCOPE_CHANGED"
                : !source.getResourceScope() ? "RESOURCE_UNAVAILABLE" : null;
        return repository.insertIfAbsent(UUID.randomUUID(), event.eventId(), event.recipientId(),
                skipReason == null ? "PENDING" : "SKIPPED", skipReason) == 1;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimDelivery(int leaseSeconds, int maxAttempts) {
        if (leaseSeconds < 120 || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Email lease must be >=120s and attempts 1..10");
        }
        repository.recoverUncertainSends();
        repository.failExhausted(maxAttempts);
        return repository.claimDelivery(UUID.randomUUID(), leaseSeconds, maxAttempts).stream().findFirst()
                .map(row -> new Claim(row.getDeliveryId(), row.getToken(), row.getAttemptCount()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PreparedEmail> prepare(Claim claim, String frontendUrl, String supportContact) {
        return loadModel(claim, frontendUrl, supportContact);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginSend(Claim claim, PreparedEmail prepared, String frontendUrl,
                             String supportContact, int maxAttempts) {
        var current = loadModel(claim, frontendUrl, supportContact);
        if (current.isEmpty()) return false;
        // Rendering may take time: recheck rights and address immediately before the durable send marker.
        if (!current.orElseThrow().equals(prepared)) {
            finish(claim, claim.attemptCount() >= maxAttempts ? "FAILED" : "PENDING", null, "EMAIL_CONTEXT_CHANGED");
            return false;
        }
        return repository.beginSend(claim.deliveryId(), claim.token()) == 1;
    }

    private Optional<PreparedEmail> loadModel(Claim claim, String frontendUrl, String supportContact) {
        var delivery = repository.findActiveClaim(claim.deliveryId(), claim.token());
        if (delivery.isEmpty()) return Optional.empty();
        var intent = outbox.findById(delivery.orElseThrow().getEventId());
        if (intent.isPresent() && "BOOKING_CANCELLED".equals(intent.orElseThrow().getEventType())) {
            return loadCancellationModel(claim, delivery.orElseThrow().getEventId(), frontendUrl, supportContact);
        }
        if (intent.isPresent() && !"BOOKING_CONFIRMED".equals(intent.orElseThrow().getEventType())) {
            try {
                var source = eventSources.resolve(intent.orElseThrow(), frontendUrl, supportContact);
                if (source.skipReason() != null) {
                    finish(claim, "SKIPPED", source.skipReason(), null);
                    return Optional.empty();
                }
                return Optional.of(new PreparedEmail(null, null, source.model()));
            } catch (InvalidBookingNotificationException invalidSource) {
                finish(claim, "FAILED", null, "EMAIL_SOURCE_UNAVAILABLE");
                return Optional.empty();
            }
        }
        var source = repository.findSendContext(claim.deliveryId(), claim.token());
        if (source.isEmpty()) {
            // A stale claim cannot update another worker's row; an invalid source becomes auditable failure.
            finish(claim, "FAILED", null, "EMAIL_SOURCE_UNAVAILABLE");
            return Optional.empty();
        }
        var context = source.orElseThrow();
        String reason = !context.getActive() ? "RECIPIENT_INACTIVE"
                : !context.getRecipientScope() ? "RECIPIENT_SCOPE_CHANGED"
                : !context.getResourceScope() ? "RESOURCE_UNAVAILABLE" : null;
        if (reason != null) {
            finish(claim, "SKIPPED", reason, null);
            return Optional.empty();
        }
        try {
            return Optional.of(new PreparedEmail(BookingConfirmedEmailModel.confirmed(context.getRecipientName(), context.getRecipientEmail(),
                    context.getBookingCode(), context.getBookingId(), context.getStationName(), context.getStationAddress(),
                    context.getChargePointCode(), context.getConnectorCode(), context.getStartAt(), context.getEndAt(),
                    context.getPaidAmount(), context.getCurrency(), frontendUrl, supportContact), null));
        } catch (IllegalArgumentException | NullPointerException invalidModel) {
            finish(claim, "FAILED", null, "EMAIL_MODEL_INVALID");
            return Optional.empty();
        }
    }

    private Optional<PreparedEmail> loadCancellationModel(Claim claim, UUID eventId, String frontendUrl, String supportContact) {
        var source = repository.findCancelledContext(eventId);
        if (source.isEmpty()) {
            finish(claim, "FAILED", null, "EMAIL_SOURCE_UNAVAILABLE");
            return Optional.empty();
        }
        var context = source.orElseThrow();
        String reason = skipReason(context);
        if (reason != null) {
            finish(claim, "SKIPPED", reason, null);
            return Optional.empty();
        }
        try {
            return Optional.of(new PreparedEmail(null, BookingCancelledEmailModel.cancelled(context, frontendUrl, supportContact)));
        } catch (IllegalArgumentException | NullPointerException invalidModel) {
            finish(claim, "FAILED", null, "EMAIL_MODEL_INVALID");
            return Optional.empty();
        }
    }

    private static String skipReason(NotificationEmailDeliveryRepository.SourceScope source) {
        return !source.getActive() ? "RECIPIENT_INACTIVE"
                : !source.getRecipientScope() ? "RECIPIENT_SCOPE_CHANGED"
                : !source.getResourceScope() ? "RESOURCE_UNAVAILABLE" : null;
    }

    public record PreparedEmail(BookingConfirmedEmailModel confirmed, BookingCancelledEmailModel cancelled, BookingEventEmailModel event) {
        public PreparedEmail(BookingConfirmedEmailModel confirmed, BookingCancelledEmailModel cancelled) { this(confirmed, cancelled, null); }
        public PreparedEmail {
            if ((confirmed == null ? 0 : 1) + (cancelled == null ? 0 : 1) + (event == null ? 0 : 1) != 1) throw new IllegalArgumentException("Exactly one email model is required");
        }
        public String recipientEmail() { return confirmed != null ? confirmed.recipientEmail() : cancelled != null ? cancelled.recipientEmail() : event.recipientEmail(); }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(Claim claim) {
        return finish(claim, "SENT", null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(Claim claim, Failure failure, int maxAttempts) {
        String status = failure == Failure.SMTP_OUTCOME_UNKNOWN ? "UNKNOWN"
                : failure.retryable && claim.attemptCount() < maxAttempts ? "PENDING" : "FAILED";
        String code = failure.retryable && claim.attemptCount() >= maxAttempts ? "SMTP_RETRY_EXHAUSTED" : failure.name();
        return finish(claim, status, null, code);
    }

    private boolean finish(Claim claim, String status, String skipReason, String code) {
        return repository.finishDelivery(claim.deliveryId(), claim.token(), status, skipReason, code) == 1;
    }

    public record Claim(UUID deliveryId, UUID token, int attemptCount) {}

    public enum Failure {
        SMTP_UNAVAILABLE(true), SMTP_TRANSIENT_REJECTION(true), SMTP_PERMANENT_REJECTION(false),
        SMTP_AUTH_REJECTED(false), SMTP_INVALID_MESSAGE(false), SMTP_OUTCOME_UNKNOWN(false);

        private final boolean retryable;
        Failure(boolean retryable) { this.retryable = retryable; }
    }
}
