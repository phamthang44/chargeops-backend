package com.thang.chargeops.notification.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.NotificationErrorCode;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.notification.model.*;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationPersistenceService persistence;
    private final SupportTicketRepository tickets;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createTicketNotice(UUID recipientId, String eventKey, String title,
                                   String body, String actionUrl, Instant createdAt) {
        var content = new NotificationContent(title, body, createdAt);
        var parsed = AppNotification.ticket(recipientId, eventKey, content, actionUrl);
        var ticket = tickets.findById(parsed.getTicketId())
                .orElseThrow(() -> new AppException(NotificationErrorCode.UNKNOWN_TICKET_SOURCE));
        if (!Objects.equals(ticket.getReporter().getId(), recipientId)
                || !eventKey.equals(ticket.getId() + ":" + ticket.getResolutionCycle() + ":RESOLVED")) {
            throw new AppException(NotificationErrorCode.TICKET_TARGET_MISMATCH);
        }
        persistence.saveIfAbsent(AppNotification.resolvedTicket(ticket, eventKey, content));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createTicketCreatedNotice(AppNotification notice) {
        persistence.saveIfAbsent(notice);
    }

    /** Infrastructure for BKG-066; domain producers are wired in that workstream. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createBookingNotice(Booking booking, NotificationEventType type, String transitionKey,
                                     Instant at, Instant expiresAt) {
        String title = switch (type) {
            case BOOKING_CONFIRMED -> NotificationI18nKey.BOOKING_CONFIRMED_TITLE;
            case BOOKING_CANCELLED -> NotificationI18nKey.BOOKING_CANCELLED_TITLE;
            case BOOKING_NO_SHOW -> NotificationI18nKey.BOOKING_NO_SHOW_TITLE;
            case BOOKING_COMPLETED -> NotificationI18nKey.BOOKING_COMPLETED_TITLE;
            case BOOKING_REMINDER -> NotificationI18nKey.BOOKING_REMINDER_TITLE;
            default -> throw new AppException(NotificationErrorCode.UNSUPPORTED_EVENT);
        };
        String body = NotificationI18nKey.format(NotificationI18nKey.BOOKING_STATE_MESSAGE,
                Map.of("code", booking.getBookingCode()));
        var content = new NotificationContent(title, body, at);
        persistence.saveIfAbsent(AppNotification.driverBooking(booking, type, transitionKey, content, expiresAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createRefundNotice(Refund refund, RefundAttempt attempt, NotificationAudience audience,
                                    NotificationEventType type, Instant at) {
        String title = switch (type) {
            case REFUND_PENDING -> NotificationI18nKey.REFUND_PENDING_TITLE;
            case REFUND_ATTEMPT_FAILED -> NotificationI18nKey.REFUND_ATTEMPT_FAILED_TITLE;
            case REFUND_SUCCEEDED -> NotificationI18nKey.REFUND_SUCCEEDED_TITLE;
            default -> throw new AppException(NotificationErrorCode.UNSUPPORTED_EVENT);
        };
        String bodyKey = switch (type) {
            case REFUND_SUCCEEDED -> NotificationI18nKey.REFUND_SUCCEEDED_BODY;
            case REFUND_PENDING -> NotificationI18nKey.REFUND_PENDING_BODY;
            case REFUND_ATTEMPT_FAILED -> NotificationI18nKey.REFUND_ATTEMPT_FAILED_BODY;
            default -> throw new AppException(NotificationErrorCode.UNSUPPORTED_EVENT);
        };
        String body = NotificationI18nKey.format(bodyKey, Map.of("code", refund.getBooking().getBookingCode()));
        persistence.saveIfAbsent(AppNotification.refundOutcome(refund, attempt, audience, type, new NotificationContent(title, body, at)));
    }

    /** Admin-facing notice that a new station registration awaits moderation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createStationSubmittedNotice(Station station, UUID adminRecipientId, Instant at) {
        String ownerName = station.getOwner().getDisplayName() != null ? station.getOwner().getDisplayName() : "";
        String title = NotificationI18nKey.format(NotificationI18nKey.STATION_SUBMITTED_TITLE,
                Map.of("stationName", station.getName()));
        String body = NotificationI18nKey.format(NotificationI18nKey.STATION_SUBMITTED_BODY,
                Map.of("ownerName", ownerName,
                        "stationName", station.getName(),
                        "stationCode", station.getStationCode()));
        var content = new NotificationContent(title, body, at);
        String sourceKey = station.getId() + ":SUBMITTED:" + adminRecipientId;
        persistence.saveIfAbsent(AppNotification.stationSubmitted(station, adminRecipientId, sourceKey, content));
    }

    /** Owner-facing decision notice (approved / rejected / changes requested). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createStationDecisionNotice(Station station, NotificationEventType type, Instant at, String reason) {
        UUID ownerRecipientId = station.getOwner().getId();
        String titleKey = switch (type) {
            case STATION_APPROVED -> NotificationI18nKey.STATION_APPROVED_TITLE;
            case STATION_REJECTED -> NotificationI18nKey.STATION_REJECTED_TITLE;
            case STATION_CHANGES_REQUESTED -> NotificationI18nKey.STATION_CHANGES_REQUESTED_TITLE;
            default -> throw new AppException(NotificationErrorCode.UNSUPPORTED_EVENT);
        };
        String bodyKey = switch (type) {
            case STATION_APPROVED -> NotificationI18nKey.STATION_APPROVED_BODY;
            case STATION_REJECTED -> NotificationI18nKey.STATION_REJECTED_BODY;
            case STATION_CHANGES_REQUESTED -> NotificationI18nKey.STATION_CHANGES_REQUESTED_BODY;
            default -> throw new AppException(NotificationErrorCode.UNSUPPORTED_EVENT);
        };
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("stationName", station.getName());
        if (reason != null && !reason.isBlank()) {
            params.put("reason", reason);
        }
        String title = NotificationI18nKey.format(titleKey, Map.of("stationName", station.getName()));
        String body = NotificationI18nKey.format(bodyKey, params);
        var content = new NotificationContent(title, body, at);
        String sourceKey = station.getId() + ":" + type.name();
        persistence.saveIfAbsent(AppNotification.stationDecision(station, type, ownerRecipientId, sourceKey, content));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean saveIfAbsent(AppNotification notice) {
        return persistence.saveIfAbsent(notice);
    }
}
