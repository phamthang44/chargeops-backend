package com.thang.chargeops.notification.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_notifications")
@Getter
@NoArgsConstructor
public class AppNotification extends BaseEntity {
    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;
    @Column(nullable = false, length = 30, updatable = false)
    private String category;
    @Column(name = "event_key", nullable = false, unique = true, length = 160, updatable = false)
    private String eventKey;
    @Column(nullable = false, length = 200, updatable = false)
    private String title;
    @Column(nullable = false, length = 2000, updatable = false)
    private String body;
    @Column(name = "action_url", nullable = false, length = 500, updatable = false)
    private String actionUrl;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;

    public static AppNotification ticket(UUID recipientId, String eventKey, String title,
                                         String body, String actionUrl, Instant createdAt) {
        AppNotification notification = new AppNotification();
        notification.recipientId = recipientId;
        notification.category = "ticket";
        notification.eventKey = eventKey;
        notification.title = title;
        notification.body = body;
        notification.actionUrl = actionUrl;
        notification.createdAt = createdAt;
        return notification;
    }
}
