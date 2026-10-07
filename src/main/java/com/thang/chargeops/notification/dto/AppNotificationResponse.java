package com.thang.chargeops.notification.dto;

import java.time.Instant;
import java.util.UUID;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.notification.entity.AppNotification;

public record AppNotificationResponse(UUID id, String category, String title, String body,
                                      String actionUrl, Instant createdAt, boolean read, Instant readAt,
                                      NotificationAudience audience, NotificationEventType eventType,
                                      Target target, Instant dismissedAt, Instant expiresAt) {
    public static AppNotificationResponse from(AppNotification notice) {
        var target = new Target(notice.getActionType(), notice.getBookingId(), notice.getTicketId(),
                notice.getStationId(), notice.getEscalationId(), notice.getRefundId());
        return new AppNotificationResponse(notice.getId(), notice.getCategory(), notice.getTitle(), notice.getBody(),
                notice.getActionUrl(), notice.getCreatedAt(), notice.getReadAt() != null, notice.getReadAt(),
                notice.getAudience(), notice.getEventType(), target, notice.getDismissedAt(), notice.getExpiresAt());
    }

    public record Target(NotificationActionType type, UUID bookingId, UUID ticketId,
                         UUID stationId, UUID escalationId, UUID refundId) {}
}
