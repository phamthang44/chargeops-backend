package com.thang.chargeops.notification.outbox;

import com.thang.chargeops.notification.kafka.BookingNotificationEvent;
import com.thang.chargeops.notification.model.NotificationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox {
    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "source_receipt_id") private UUID sourceReceiptId;
    @Column(name = "source_command_id") private UUID sourceCommandId;
    @Column(name = "source_history_id") private UUID sourceHistoryId;
    @Column(name = "source_decision_id") private UUID sourceDecisionId;
    @Column(name = "source_refund_id") private UUID sourceRefundId;
    @Column(name = "source_attempt_id") private UUID sourceAttemptId;
    @Column(name = "reminder_start_at") private Instant reminderStartAt;
    @Column(name = "source_event_key", nullable = false, length = 160) private String sourceEventKey;
    @Column(name = "booking_id", nullable = false) private UUID bookingId;
    @Column(name = "station_id", nullable = false) private UUID stationId;
    @Column(name = "recipient_id", nullable = false) private UUID recipientId;
    @Column(nullable = false, length = 20) private String audience;
    @Column(nullable = false, length = 20) private String channel;
    @Column(name = "event_type", nullable = false, length = 40) private String eventType;
    @Column(name = "schema_version", nullable = false) private int schemaVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "claim_token") private UUID claimToken;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "sanitized_last_error", length = 500) private String sanitizedLastError;

    public static NotificationOutbox cancelled(BookingNotificationEvent event,
                                                UUID commandId, String payload) {
        if (event.eventType() != NotificationEventType.BOOKING_CANCELLED
                || !event.sourceEventKey().equals(commandId + ":BOOKING_CANCELLED")) {
            throw new IllegalArgumentException("Cancellation requires its persisted command identity");
        }
        var row = new NotificationOutbox();
        row.eventId = event.eventId();
        row.sourceCommandId = commandId;
        row.sourceEventKey = event.sourceEventKey();
        row.bookingId = event.bookingId();
        row.stationId = event.stationId();
        row.recipientId = event.recipientId();
        row.audience = event.audience().name();
        row.channel = "EMAIL";
        row.eventType = event.eventType().name();
        row.schemaVersion = event.schemaVersion();
        row.payload = payload;
        row.occurredAt = event.occurredAt();
        row.nextAttemptAt = event.occurredAt();
        row.status = "PENDING";
        return row;
    }

    public static NotificationOutbox sourced(BookingNotificationEvent event, String payload,
            UUID historyId, UUID decisionId, UUID refundId, UUID attemptId, Instant reminderStartAt) {
        var row = new NotificationOutbox();
        row.eventId = event.eventId();
        row.sourceHistoryId = historyId;
        row.sourceDecisionId = decisionId;
        row.sourceRefundId = refundId;
        row.sourceAttemptId = attemptId;
        row.reminderStartAt = reminderStartAt;
        row.sourceEventKey = event.sourceEventKey();
        row.bookingId = event.bookingId();
        row.stationId = event.stationId();
        row.recipientId = event.recipientId();
        row.audience = event.audience().name();
        row.channel = "EMAIL";
        row.eventType = event.eventType().name();
        row.schemaVersion = event.schemaVersion();
        row.payload = payload;
        row.occurredAt = event.occurredAt();
        row.nextAttemptAt = event.occurredAt();
        row.status = "PENDING";
        return row;
    }
}
