package com.thang.chargeops.notification.email;

import org.springframework.data.jpa.repository.JpaRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.refund.model.RefundStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import java.math.BigDecimal;

public interface NotificationEmailDeliveryRepository extends JpaRepository<NotificationEmailDelivery, UUID> {
    @Query("""
            SELECT CASE WHEN u.status = 'ACTIVE' AND u.deletedAt IS NULL THEN true ELSE false END AS active,
                CASE WHEN b.driver.id = o.recipientId THEN true ELSE false END AS recipientScope,
                CASE WHEN cp.station.id = o.stationId AND s.id IS NOT NULL AND c.id IS NOT NULL
                    AND cp.id IS NOT NULL AND s.deletedAt IS NULL AND c.deletedAt IS NULL
                    AND cp.deletedAt IS NULL THEN true ELSE false END AS resourceScope,
                u.displayName AS recipientName, u.email AS recipientEmail, b.id AS bookingId,
                b.bookingCode AS bookingCode, b.stationNameSnapshot AS stationName,
                b.stationAddressSnapshot AS stationAddress, b.startAt AS startAt, b.endAt AS endAt,
                h.occurredAt AS cancelledAt, h.fromStatus AS previousStatus, h.reason AS cancellationReason,
                refund.amount AS refundAmount, refund.status AS refundStatus
            FROM NotificationOutbox o
            JOIN BookingCommand command ON command.id = o.sourceCommandId
            JOIN BookingStatusHistory h ON h.command.id = command.id AND h.booking.id = o.bookingId
            JOIN Booking b ON b.id = o.bookingId
            LEFT JOIN UserProfile u ON u.id = o.recipientId
            LEFT JOIN Connector c ON c.id = b.connector.id
            LEFT JOIN ChargePoint cp ON cp.id = c.chargePoint.id
            LEFT JOIN Station s ON s.id = o.stationId
            LEFT JOIN BookingServiceFailureDecision decision ON decision.command.id = command.id AND decision.booking.id = b.id
            LEFT JOIN Refund refund ON (refund.basisId = command.id AND refund.basisType = 'BOOKING_CANCELLATION')
                OR (refund.id = decision.refund.id AND refund.booking.id = b.id)
            WHERE o.eventId = :eventId AND o.eventType = 'BOOKING_CANCELLED' AND o.audience = 'DRIVER'
                AND o.channel = 'EMAIL' AND o.sourceReceiptId IS NULL
                AND command.booking.id = b.id AND h.actor.id = command.actor.id
                AND o.sourceEventKey = concat(cast(command.id AS string), ':BOOKING_CANCELLED')
                AND h.toStatus = 'CANCELLED'
                AND h.fromStatus IN ('PENDING', 'CONFIRMED') AND h.occurredAt = o.occurredAt
                AND b.status = 'CANCELLED'
                AND ((command.operation = 'CANCEL_BOOKING' AND command.actor.id = o.recipientId
                    AND h.actorType = 'DRIVER' AND h.reason = 'DRIVER_CANCELLED' AND b.cancellationReason = 'DRIVER_CANCELLED')
                  OR (command.operation = 'OWNER_CANCEL_BOOKING' AND command.actor.id = s.owner.id
                    AND h.actorType = 'OWNER' AND h.reason = 'OWNER_CANCELLED' AND b.cancellationReason = 'STATION_FAILURE'
                    AND decision.actorKind = 'OWNER' AND decision.kind = 'OWNER_CANCEL_BOOKING'
                    AND decision.actorProfileId = command.actor.id AND decision.stationId = s.id)
                  OR (command.operation = 'ADMIN_REVIEW_STATION_FAILURE' AND h.actorType = 'ADMIN'
                    AND h.reason = 'ADMIN_POLICY_CANCELLED' AND b.cancellationReason = 'STATION_FAILURE'
                    AND decision.actorKind = 'ADMIN' AND decision.result = 'FULL_REFUND'
                    AND decision.actorProfileId = command.actor.id AND decision.stationId = s.id
                    AND decision.ticketId IS NOT NULL AND decision.escalationId IS NOT NULL))
            """)
    Optional<CancellationContext> findCancelledContext(@Param("eventId") UUID eventId);

    @Query("""
            SELECT d FROM NotificationEmailDelivery d WHERE d.deliveryId = :deliveryId AND d.claimToken = :token
                AND d.status = 'PROCESSING' AND d.leaseUntil > CURRENT_TIMESTAMP AND d.sendStartedAt IS NULL
            """)
    Optional<NotificationEmailDelivery> findActiveClaim(@Param("deliveryId") UUID deliveryId, @Param("token") UUID token);

    @Query(value = """
                SELECT u.status = 'ACTIVE' AND u.deleted_at IS NULL AS active,
                    b.driver_id = o.recipient_id AS "recipientScope",
                    cp.station_id = o.station_id AND s.deleted_at IS NULL
                        AND c.deleted_at IS NULL AND cp.deleted_at IS NULL AS "resourceScope"
                FROM notification_outbox o
                JOIN bookings b ON b.id = o.booking_id
                JOIN connectors c ON c.id = b.connector_id
                JOIN charge_points cp ON cp.id = c.charge_point_id
                JOIN stations s ON s.id = o.station_id
                JOIN user_profile u ON u.id = o.recipient_id
                JOIN payment_transactions receipt ON receipt.id = o.source_receipt_id
                JOIN payments p ON p.id = receipt.payment_id
                WHERE o.event_id = :eventId AND o.payload = CAST(:payload AS jsonb)
                    AND receipt.application_classification = 'APPLIED' AND p.booking_id = o.booking_id
                    AND o.source_event_key = receipt.id::text || ':BOOKING_CONFIRMED'
            """, nativeQuery = true)
    Optional<SourceScope> findConfirmedSource(@Param("eventId") UUID eventId, @Param("payload") String payload);

    // Atomic conflict-ignore insert preserves delivery state across concurrent replays.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                INSERT INTO notification_email_deliveries(delivery_id, event_id, recipient_id, status, skip_reason)
                VALUES (:deliveryId, :eventId, :recipientId, :status, :skipReason)
                ON CONFLICT(event_id, recipient_id, channel) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("deliveryId") UUID deliveryId, @Param("eventId") UUID eventId,
                       @Param("recipientId") UUID recipientId, @Param("status") String status,
                       @Param("skipReason") String skipReason);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE notification_email_deliveries SET status = 'UNKNOWN', claim_token = NULL,
                lease_until = NULL, sanitized_last_error = 'SMTP_LEASE_EXPIRED_AFTER_START'
            WHERE status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP AND send_started_at IS NOT NULL
            """, nativeQuery = true)
    int recoverUncertainSends();

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE notification_email_deliveries SET status = 'FAILED', claim_token = NULL,
                lease_until = NULL, sanitized_last_error = 'SMTP_RETRY_EXHAUSTED'
            WHERE attempt_count >= :maxAttempts AND send_started_at IS NULL
                AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP))
            """, nativeQuery = true)
    int failExhausted(@Param("maxAttempts") int maxAttempts);

    @Query(value = """
            WITH candidate AS (
                SELECT delivery_id FROM notification_email_deliveries
                WHERE attempt_count < :maxAttempts AND send_started_at IS NULL
                    AND ((status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                      OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP))
                ORDER BY next_attempt_at, created_at, delivery_id FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE notification_email_deliveries d SET status = 'PROCESSING', claim_token = :token,
                lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
                attempt_count = attempt_count + 1
            FROM candidate c WHERE d.delivery_id = c.delivery_id
            RETURNING d.delivery_id AS "deliveryId", d.claim_token AS "token", d.attempt_count AS "attemptCount"
            """, nativeQuery = true)
    List<DeliveryClaim> claimDelivery(@Param("token") UUID token, @Param("leaseSeconds") int leaseSeconds,
                                     @Param("maxAttempts") int maxAttempts);

    @Query(value = """
            SELECT u.status = 'ACTIVE' AND u.deleted_at IS NULL AS active,
                b.driver_id = d.recipient_id AS "recipientScope",
                cp.station_id = o.station_id AND s.deleted_at IS NULL AND cp.deleted_at IS NULL
                    AND c.deleted_at IS NULL AND b.status = 'CONFIRMED' AND p.status = 'PAID' AS "resourceScope",
                u.display_name AS "recipientName", u.email AS "recipientEmail", b.id AS "bookingId",
                b.booking_code AS "bookingCode", b.station_name_snapshot AS "stationName",
                b.station_address_snapshot AS "stationAddress", b.charge_point_code_snapshot AS "chargePointCode",
                b.connector_code_snapshot AS "connectorCode", b.start_at AS "startAt", b.end_at AS "endAt",
                p.amount AS "paidAmount", p.currency AS currency
            FROM notification_email_deliveries d
            JOIN notification_outbox o ON o.event_id = d.event_id AND o.recipient_id = d.recipient_id
            JOIN bookings b ON b.id = o.booking_id
            JOIN connectors c ON c.id = b.connector_id
            JOIN charge_points cp ON cp.id = c.charge_point_id
            JOIN stations s ON s.id = o.station_id
            JOIN user_profile u ON u.id = d.recipient_id
            JOIN payment_transactions receipt ON receipt.id = o.source_receipt_id
            JOIN payments p ON p.id = receipt.payment_id
            WHERE d.delivery_id = :deliveryId AND d.claim_token = :token AND d.status = 'PROCESSING'
                AND d.lease_until > CURRENT_TIMESTAMP AND d.send_started_at IS NULL
                AND receipt.application_classification = 'APPLIED' AND p.booking_id = b.id
                AND o.source_event_key = receipt.id::text || ':BOOKING_CONFIRMED'
                AND o.event_type = 'BOOKING_CONFIRMED' AND o.audience = 'DRIVER' AND d.channel = 'EMAIL'
            """, nativeQuery = true)
    Optional<SendContext> findSendContext(@Param("deliveryId") UUID deliveryId, @Param("token") UUID token);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE notification_email_deliveries SET send_started_at = CURRENT_TIMESTAMP
            WHERE delivery_id = :deliveryId AND claim_token = :token AND status = 'PROCESSING'
                AND lease_until > CURRENT_TIMESTAMP AND send_started_at IS NULL
            """, nativeQuery = true)
    int beginSend(@Param("deliveryId") UUID deliveryId, @Param("token") UUID token);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE notification_email_deliveries SET status = :status, claim_token = NULL, lease_until = NULL,
                skip_reason = :skipReason, sanitized_last_error = :failureCode,
                send_started_at = CASE WHEN :status = 'PENDING' THEN NULL ELSE send_started_at END,
                sent_at = CASE WHEN :status = 'SENT' THEN CURRENT_TIMESTAMP ELSE NULL END,
                next_attempt_at = CASE WHEN :status = 'PENDING' THEN CURRENT_TIMESTAMP
                    + (LEAST(300, 5 * power(2, LEAST(attempt_count - 1, 6))) * INTERVAL '1 second')
                    ELSE next_attempt_at END
            WHERE delivery_id = :deliveryId AND claim_token = :token AND status = 'PROCESSING'
                AND (:status <> 'SENT' OR send_started_at IS NOT NULL)
                AND (:status <> 'UNKNOWN' OR send_started_at IS NOT NULL)
                AND (:status <> 'SKIPPED' OR send_started_at IS NULL)
            """, nativeQuery = true)
    int finishDelivery(@Param("deliveryId") UUID deliveryId, @Param("token") UUID token,
                       @Param("status") String status, @Param("skipReason") String skipReason,
                       @Param("failureCode") String failureCode);

    interface DeliveryClaim {
        UUID getDeliveryId();
        UUID getToken();
        int getAttemptCount();
    }

    interface SendContext extends SourceScope {
        String getRecipientName();
        String getRecipientEmail();
        UUID getBookingId();
        String getBookingCode();
        String getStationName();
        String getStationAddress();
        String getChargePointCode();
        String getConnectorCode();
        Instant getStartAt();
        Instant getEndAt();
        BigDecimal getPaidAmount();
        String getCurrency();
    }

    interface SourceScope {
        boolean getActive();
        boolean getRecipientScope();
        boolean getResourceScope();
    }

    interface CancellationContext extends SourceScope {
        String getCancellationReason();
        String getRecipientName();
        String getRecipientEmail();
        UUID getBookingId();
        String getBookingCode();
        String getStationName();
        String getStationAddress();
        Instant getStartAt();
        Instant getEndAt();
        Instant getCancelledAt();
        BookingStatus getPreviousStatus();
        BigDecimal getRefundAmount();
        RefundStatus getRefundStatus();
    }
}
