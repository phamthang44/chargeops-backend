package com.thang.chargeops.notification.outbox;

import com.thang.chargeops.notification.kafka.BookingNotificationEvent;
import com.thang.chargeops.notification.kafka.BookingNotificationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** At-least-once transport: broker acknowledgement precedes the independently committed DB marker. */
public class BookingNotificationOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationOutboxWorker.class);
    private final NotificationOutboxStore store;
    private final BookingNotificationPublisher publisher;
    private final ObjectMapper json;
    private final int batchSize;
    private final int ackTimeoutSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("booking-outbox-").factory());

    public BookingNotificationOutboxWorker(NotificationOutboxStore store, BookingNotificationPublisher publisher,
                                         ObjectMapper json, int batchSize, int ackTimeoutSeconds) {
        if (batchSize < 1 || batchSize > 100 || ackTimeoutSeconds < 1 || ackTimeoutSeconds > 60) {
            throw new IllegalArgumentException("Outbox batch must be 1..100 and ACK timeout 1..60 seconds");
        }
        this.store = store;
        this.publisher = publisher;
        this.json = json;
        this.batchSize = batchSize;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${app.notification.outbox.poll-delay-ms:5000}",
            initialDelayString = "${app.notification.outbox.initial-delay-ms:10000}")
    public void poll() {
        // Keep the shared Spring scheduler available for expiration and refund jobs, with no queued backlog.
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    dispatchPending();
                } catch (RuntimeException failure) {
                    log.warn("Outbox poll failed; committed leases remain recoverable, code={}",
                            failure.getClass().getSimpleName());
                } finally {
                    scheduled.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            scheduled.set(false);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public void dispatchPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox worker must run outside a database transaction");
        }
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
                var next = store.claimNext(180);
                if (next.isEmpty()) return;
                var claim = next.orElseThrow();
                BookingNotificationEvent event;
                try {
                    event = json.readValue(claim.payload(), BookingNotificationEvent.class);
                    if (!claim.eventId().equals(event.eventId())) throw new IllegalArgumentException("Event identity mismatch");
                } catch (RuntimeException invalid) {
                    store.retryLater(claim, "INVALID_EVENT");
                    log.warn("Outbox invalid event: eventId={}", claim.eventId());
                    continue;
                }
                try {
                    publisher.publish(event).get(ackTimeoutSeconds, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // Leave the lease for recovery; an in-flight send may already have reached Kafka.
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    String code = failure instanceof TimeoutException ? "KAFKA_ACK_TIMEOUT" : "KAFKA_PUBLISH_FAILED";
                    store.retryLater(claim, code);
                    log.warn("Outbox publish deferred: eventId={}, attempt={}, code={}",
                            claim.eventId(), claim.attemptCount(), code);
                    continue;
                }
                if (!store.markPublished(claim)) {
                    log.warn("Outbox ACK ignored for superseded claim: eventId={}", claim.eventId());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
