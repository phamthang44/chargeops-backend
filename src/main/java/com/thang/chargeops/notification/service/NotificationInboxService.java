package com.thang.chargeops.notification.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.notification.dto.AppNotificationResponse;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.event.NotificationHintEvent;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import static com.thang.chargeops.notification.model.NotificationConstants.*;

@Service
@RequiredArgsConstructor
public class NotificationInboxService {
    private final AppNotificationRepository repository;
    private final CurrentProfileProvider currentProfile;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private static final Sort INBOX_ORDER = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    @Transactional(readOnly = true)
    public Page<AppNotificationResponse> list(int page, int size, boolean unread) {
        return listInScope(page, size, unread, null, null, null);
    }

    @Transactional(readOnly = true)
    public Page<AppNotificationResponse> list(int page, int size, boolean unread, String context, String category, UUID stationId) {
        return listInScope(page, size, unread, context, category, stationId);
    }

    private Page<AppNotificationResponse> listInScope(int page, int size, boolean unread, String context, String category, UUID stationId) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        var scope = NotificationVisibilityPolicy.inScope(filter(context, category, stationId, unread));
        return repository.findAll(scope, PageRequest.of(page - 1, size, INBOX_ORDER))
                .map(AppNotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount() { return countUnreadInScope(null, null, null); }

    @Transactional(readOnly = true)
    public long unreadCount(String context, String category, UUID stationId) {
        return countUnreadInScope(context, category, stationId);
    }

    private long countUnreadInScope(String context, String category, UUID stationId) {
        return repository.count(NotificationVisibilityPolicy.inScope(filter(context, category, stationId, true)));
    }

    @Transactional
    public void markRead(UUID id) { markReadInScope(id, null, null, null); }

    @Transactional
    public void markRead(UUID id, String context, String category, UUID stationId) {
        markReadInScope(id, context, category, stationId);
    }

    private void markReadInScope(UUID id, String context, String category, UUID stationId) {
        var f = filter(context, category, stationId, false);
        var notice = requireVisible(id, f, false);
        boolean transitioned = notice.getReadAt() == null;
        notice.markRead(f.now());
        repository.save(notice);
        // Hint only on the real transition: a repeat markRead must not re-notify (plan #69 §4.3).
        if (transitioned) {
            events.publishEvent(NotificationHintEvent.read(f.recipientKeycloakId(), notice.getId().toString()));
        }
    }

    @Transactional
    public void markAllRead() { markAllReadInScope(null, null, null); }

    @Transactional
    public void markAllRead(String context, String category, UUID stationId) {
        markAllReadInScope(context, category, stationId);
    }

    private void markAllReadInScope(String context, String category, UUID stationId) {
        var f = filter(context, category, stationId, true);
        var scope = NotificationVisibilityPolicy.inScope(f);
        var notices = repository.findAll(scope);
        long transitioned = notices.stream().filter(notice -> notice.getReadAt() == null).count();
        notices.forEach(notice -> notice.markRead(f.now()));
        repository.saveAll(notices);
        // Exactly one READ_ALL hint no matter how many rows changed — never N hints (plan #69 §4.3).
        if (transitioned > 0) {
            events.publishEvent(NotificationHintEvent.readAll(
                    f.recipientKeycloakId(), scopeKey(context, category, stationId)));
        }
    }

    private static String scopeKey(String context, String category, UUID stationId) {
        return (context == null ? "all" : context) + "|"
                + (category == null ? "all" : category) + "|"
                + (stationId == null ? "all" : stationId) + "|all";
    }

    @Transactional
    public void dismiss(UUID id, String context, String category, UUID stationId) {
        var f = filter(context, category, stationId, false);
        var notice = requireVisible(id, f, true);
        boolean transitioned = notice.getDismissedAt() == null;
        notice.dismiss(f.now());
        repository.save(notice);
        if (transitioned) {
            events.publishEvent(NotificationHintEvent.dismissed(
                    f.recipientKeycloakId(), notice.getId().toString()));
        }
    }

    private AppNotification requireVisible(UUID id, NotificationInboxFilter filter, boolean includeDismissed) {
        Specification<AppNotification> matchingId = (notice, query, builder) -> builder.equal(notice.get("id"), id);
        var scope = NotificationVisibilityPolicy.inScope(filter, includeDismissed).and(matchingId);
        return repository.findOne(scope)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private NotificationInboxFilter filter(String context, String category, UUID stationId, boolean unread) {
        var profile = currentProfile.requireProfile();
        if (profile.getStatus() != UserStatus.ACTIVE || profile.isDeleted()) throw new AppException(AuthErrorCode.ACCESS_DENIED);
        NotificationAudience audience;
        try {
            audience = context == null ? NotificationAudience.PERSONAL : NotificationAudience.valueOf(context.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { throw new AppException(CommonErrorCode.INVALID_REQUEST); }
        if (audience != NotificationAudience.PERSONAL) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream()
                    .noneMatch(a -> a.getAuthority().equals(ROLE_PREFIX + audience.name()))) {
                throw new AppException(AuthErrorCode.ACCESS_DENIED);
            }
        }
        if (category != null && !CATEGORIES.contains(category)) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        return new NotificationInboxFilter(profile.getId(), profile.getKeycloakId(),
                audience, category, stationId, unread, clock.instant());
    }
}
