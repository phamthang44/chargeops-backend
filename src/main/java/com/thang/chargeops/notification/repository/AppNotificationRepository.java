package com.thang.chargeops.notification.repository;

import com.thang.chargeops.notification.entity.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import com.thang.chargeops.notification.model.NotificationAudience;
import java.util.UUID;

public interface AppNotificationRepository extends JpaRepository<AppNotification, UUID>,
        JpaSpecificationExecutor<AppNotification> {
    boolean existsByRecipientIdAndAudienceAndSourceEventKey(
            UUID recipientId, NotificationAudience audience, String sourceEventKey);
}
