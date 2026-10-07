package com.thang.chargeops.notification.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.NotificationErrorCode;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {
    private final AppNotificationRepository repository;
    private final UserProfileRepository profiles;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean saveIfAbsent(AppNotification notice) {
        // Serialize inserts for this recipient before checking the unique intent key.
        profiles.findByIdWithLock(notice.getRecipientId())
                .orElseThrow(() -> new AppException(NotificationErrorCode.UNKNOWN_RECIPIENT));
        if (repository.existsByRecipientIdAndAudienceAndSourceEventKey(
                notice.getRecipientId(), notice.getAudience(), notice.getSourceEventKey())) {
            return false;
        }
        repository.saveAndFlush(notice);
        return true;
    }
}
