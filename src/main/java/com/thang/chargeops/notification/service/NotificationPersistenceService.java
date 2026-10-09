package com.thang.chargeops.notification.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.NotificationErrorCode;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.event.NotificationHintEvent;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {
    private final AppNotificationRepository repository;
    private final UserProfileRepository profiles;
    private final ApplicationEventPublisher events;

    /**
     * MANDATORY so the realtime hint is always published inside the caller's transaction and
     * delivered only on commit (BKG-071 plan #69 §4.1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean saveIfAbsent(AppNotification notice) {
        // Serialize inserts for this recipient before checking the unique intent key.
        var profile = profiles.findByIdWithLock(notice.getRecipientId())
                .orElseThrow(() -> new AppException(NotificationErrorCode.UNKNOWN_RECIPIENT));
        if (repository.existsByRecipientIdAndAudienceAndSourceEventKey(
                notice.getRecipientId(), notice.getAudience(), notice.getSourceEventKey())) {
            return false;
        }
        repository.saveAndFlush(notice);
        // The locked profile already carries the keycloak id: no extra query, and the recipient
        // is resolved before commit so the AFTER_COMMIT listener never touches the database.
        events.publishEvent(NotificationHintEvent.created(
                profile.getKeycloakId(), notice.getSourceEventKey()));
        return true;
    }
}
