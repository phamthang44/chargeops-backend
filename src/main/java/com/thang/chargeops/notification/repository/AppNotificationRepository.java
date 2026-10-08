package com.thang.chargeops.notification.repository;

import com.thang.chargeops.notification.entity.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.thang.chargeops.notification.model.NotificationAudience;
import java.util.UUID;

public interface AppNotificationRepository extends JpaRepository<AppNotification, UUID>,
        JpaSpecificationExecutor<AppNotification> {
    boolean existsByRecipientIdAndAudienceAndSourceEventKey(
            UUID recipientId, NotificationAudience audience, String sourceEventKey);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO app_notifications(recipient_id,category,event_key,source_event_key,audience,event_type,
                booking_id,station_id,refund_id,expires_at,action_type,title,body,action_url,created_at)
            VALUES (:#{#notice.recipientId}, :#{#notice.category}, :#{#notice.eventKey},
                :#{#notice.sourceEventKey}, :#{#notice.audience.name()}, :#{#notice.eventType.name()},
                :#{#notice.bookingId}, :#{#notice.stationId}, :#{#notice.refundId}, :#{#notice.expiresAt}, :#{#notice.actionType.name()},
                :#{#notice.title}, :#{#notice.body}, :#{#notice.actionUrl}, :#{#notice.createdAt})
            ON CONFLICT (recipient_id,audience,source_event_key) DO NOTHING
            """, nativeQuery = true)
    int insertBookingIntentIfAbsent(@Param("notice") AppNotification notice);
}
