package com.thang.chargeops.notification.outbox;

import com.thang.chargeops.notification.kafka.BookingNotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {
    boolean existsBySourceEventKeyAndRecipientIdAndAudienceAndChannel(
            String sourceEventKey, UUID recipientId, String audience, String channel);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO notification_outbox(event_id,source_receipt_id,source_event_key,booking_id,station_id,
                recipient_id,audience,event_type,schema_version,payload,occurred_at,next_attempt_at)
            VALUES (:#{#event.eventId()}, :receiptId, :#{#event.sourceEventKey()}, :#{#event.bookingId()},
                :#{#event.stationId()}, :#{#event.recipientId()}, :#{#event.audience().name()},
                :#{#event.eventType().name()}, :#{#event.schemaVersion()}, CAST(:payload AS jsonb),
                :#{#event.occurredAt()}, :#{#event.occurredAt()})
            ON CONFLICT (source_event_key,recipient_id,audience,channel) DO NOTHING
            """, nativeQuery = true)
    int enqueueIfAbsent(@Param("event") BookingNotificationEvent event,
                        @Param("receiptId") UUID receiptId, @Param("payload") String payload);

    @Query(value = """
                WITH candidate AS (
                    SELECT event_id FROM notification_outbox
                    WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, occurred_at, event_id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE notification_outbox o SET status = 'PROCESSING', claim_token = :token,
                    lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
                    attempt_count = attempt_count + 1
                FROM candidate c WHERE o.event_id = c.event_id
                RETURNING o.event_id AS "eventId", o.claim_token AS "token", o.payload::text AS "payload", o.attempt_count AS "attemptCount"
            """, nativeQuery = true)
    List<ClaimView> claimNext(@Param("token") UUID token, @Param("leaseSeconds") int leaseSeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                UPDATE notification_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    claim_token = NULL, lease_until = NULL, sanitized_last_error = NULL
                WHERE event_id = :eventId AND status = 'PROCESSING' AND claim_token = :token
            """, nativeQuery = true)
    int markPublished(@Param("eventId") UUID eventId, @Param("token") UUID token);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                UPDATE notification_outbox SET status = 'PENDING', claim_token = NULL, lease_until = NULL,
                    sanitized_last_error = :failureCode, next_attempt_at = CURRENT_TIMESTAMP
                        + (LEAST(300, 5 * power(2, LEAST(attempt_count - 1, 6))) * INTERVAL '1 second')
                WHERE event_id = :eventId AND status = 'PROCESSING' AND claim_token = :token
            """, nativeQuery = true)
    int retryLater(@Param("eventId") UUID eventId, @Param("token") UUID token, @Param("failureCode") String failureCode);

    interface ClaimView {
        UUID getEventId();
        UUID getToken();
        String getPayload();
        int getAttemptCount();
    }
}
