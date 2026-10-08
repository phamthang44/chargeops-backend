package com.thang.chargeops.notification.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_email_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEmailDelivery {
    @Id
    @Column(name = "delivery_id") private UUID deliveryId;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @Column(name = "recipient_id", nullable = false) private UUID recipientId;
    @Column(name = "channel", nullable = false, length = 20) private String channel;
    @Column(name = "status", nullable = false, length = 20) private String status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "claim_token") private UUID claimToken;
    @Column(name = "send_started_at") private Instant sendStartedAt;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "skip_reason", length = 40) private String skipReason;
    @Column(name = "sanitized_last_error", length = 500) private String sanitizedLastError;
}
