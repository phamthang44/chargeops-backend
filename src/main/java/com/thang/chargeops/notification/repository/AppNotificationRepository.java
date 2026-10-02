package com.thang.chargeops.notification.repository;

import com.thang.chargeops.notification.entity.AppNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface AppNotificationRepository extends JpaRepository<AppNotification, UUID> {
    Page<AppNotification> findByRecipientId(UUID recipientId, Pageable pageable);
    Page<AppNotification> findByRecipientIdAndReadAtIsNull(UUID recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(UUID recipientId);
    boolean existsByIdAndRecipientId(UUID id, UUID recipientId);

    @Modifying
    @Query("update AppNotification n set n.readAt = :readAt where n.id = :id and n.recipientId = :recipientId and n.readAt is null")
    int markRead(UUID id, UUID recipientId, Instant readAt);

    @Modifying
    @Query("update AppNotification n set n.readAt = :readAt where n.recipientId = :recipientId and n.readAt is null")
    int markAllRead(UUID recipientId, Instant readAt);
}
