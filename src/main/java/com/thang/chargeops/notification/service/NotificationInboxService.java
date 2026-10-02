package com.thang.chargeops.notification.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.notification.dto.AppNotificationResponse;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationInboxService {
    private final AppNotificationRepository repository;
    private final CurrentProfileProvider currentProfile;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<AppNotificationResponse> list(int page, int size, boolean unread) {
        UUID userId = currentProfile.requireProfile().getId();
        var pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AppNotification> notifications = unread
                ? repository.findByRecipientIdAndReadAtIsNull(userId, pageable)
                : repository.findByRecipientId(userId, pageable);
        return notifications.map(n -> new AppNotificationResponse(n.getId(), n.getCategory(), n.getTitle(),
                n.getBody(), n.getActionUrl(), n.getCreatedAt(), n.getReadAt() != null, n.getReadAt()));
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByRecipientIdAndReadAtIsNull(currentProfile.requireProfile().getId());
    }

    @Transactional
    public void markRead(UUID id) {
        UUID userId = currentProfile.requireProfile().getId();
        if (repository.markRead(id, userId, clock.instant()) == 0 && !repository.existsByIdAndRecipientId(id, userId)) {
            throw new AppException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Transactional
    public void markAllRead() {
        repository.markAllRead(currentProfile.requireProfile().getId(), clock.instant());
    }
}
