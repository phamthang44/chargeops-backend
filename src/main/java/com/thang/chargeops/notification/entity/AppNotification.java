package com.thang.chargeops.notification.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.NotificationErrorCode;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.model.RefundAttemptStatus;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.thang.chargeops.notification.model.NotificationConstants.*;

@Entity
@Table(name = "app_notifications")
@Getter
@NoArgsConstructor
public class AppNotification extends BaseEntity {
    private static final Pattern TICKET_EVENT_KEY = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}:[0-9]+:RESOLVED");
    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;
    @Column(nullable = false, length = 30, updatable = false)
    private String category;
    @Column(name = "event_key", nullable = false, length = EVENT_KEY_MAX_LENGTH, updatable = false)
    private String eventKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private NotificationAudience audience;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private NotificationEventType eventType;
    @Column(name = "source_event_key", nullable = false, length = EVENT_KEY_MAX_LENGTH, updatable = false)
    private String sourceEventKey;
    @Column(name = "booking_id", updatable = false)
    private UUID bookingId;
    @Column(name = "ticket_id", updatable = false)
    private UUID ticketId;
    @Column(name = "escalation_id", updatable = false)
    private UUID escalationId;
    @Column(name = "station_id", updatable = false)
    private UUID stationId;
    @Column(name = "refund_id", updatable = false)
    private UUID refundId;
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30, updatable = false)
    private NotificationActionType actionType;
    @Column(nullable = false, length = TITLE_MAX_LENGTH, updatable = false)
    private String title;
    @Column(nullable = false, length = BODY_MAX_LENGTH, updatable = false)
    private String body;
    @Column(name = "action_url", nullable = false, length = 500, updatable = false)
    private String actionUrl;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "dismissed_at")
    private Instant dismissedAt;
    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Version
    private long version;

    public void markRead(Instant at) {
        if (readAt == null) readAt = at;
    }

    public void dismiss(Instant at) {
        if (dismissedAt == null) dismissedAt = at;
    }

    public static AppNotification ticket(UUID recipientId, String eventKey, NotificationContent content, String actionUrl) {
        if (eventKey == null || !TICKET_EVENT_KEY.matcher(eventKey).matches()) {
            throw new AppException(NotificationErrorCode.TICKET_TARGET_MISMATCH);
        }
        UUID ticketId = UUID.fromString(eventKey.split(":")[0]);
        if (!(TICKET_PATH + ticketId).equals(actionUrl)) {
            throw new AppException(NotificationErrorCode.TICKET_TARGET_MISMATCH);
        }
        AppNotification notification = base(recipientId, NotificationAudience.PERSONAL,
                NotificationEventType.TICKET_RESOLVED, CATEGORY_TICKET, eventKey, content);
        notification.ticketId = ticketId;
        notification.actionType = NotificationActionType.OPEN_TICKET;
        notification.actionUrl = actionUrl;
        return notification;
    }

    public static AppNotification resolvedTicket(SupportTicket ticket, String eventKey, NotificationContent content) {
        var n = ticket(ticket.getReporter().getId(), eventKey, content, TICKET_PATH + ticket.getId());
        if (!n.ticketId.equals(ticket.getId())) throw new AppException(NotificationErrorCode.TICKET_TARGET_MISMATCH);
        n.setTicketTarget(ticket, NotificationActionType.OPEN_TICKET);
        return n;
    }

    public static AppNotification createdTicket(SupportTicket ticket, UUID recipientId,
                                                NotificationAudience audience, String sourceKey,
                                                NotificationContent content) {
        if (ticket.getId() == null
                || (audience == NotificationAudience.ADMIN) != (ticket.getStation() == null)
                || (audience != NotificationAudience.ADMIN && audience != NotificationAudience.OWNER)) {
            throw new AppException(NotificationErrorCode.INVALID_INTENT);
        }
        var n = base(recipientId, audience, NotificationEventType.TICKET_CREATED,
                CATEGORY_TICKET, sourceKey, content);
        n.setTicketTarget(ticket, NotificationActionType.OPEN_TICKET);
        return n;
    }

    /** Recipient and resource IDs come from persisted domain objects, never a client request. */
    public static AppNotification driverBooking(Booking booking, NotificationEventType type,
                                                 String transitionKey, NotificationContent content, Instant expiresAt) {
        if (booking.getId() == null || !isBookingEvent(type)
                || (type == NotificationEventType.BOOKING_REMINDER && expiresAt == null)) {
            throw new AppException(NotificationErrorCode.INVALID_INTENT);
        }
        var n = base(booking.getDriver().getId(), NotificationAudience.DRIVER, type, CATEGORY_BOOKING,
                transitionKey, content);
        n.setBookingTarget(booking);
        if (expiresAt != null && !expiresAt.isAfter(content.createdAt())) throw new AppException(NotificationErrorCode.INVALID_EXPIRY);
        n.expiresAt = expiresAt;
        return n;
    }

    public static AppNotification refundOutcome(Refund refund, RefundAttempt attempt,
                                                 NotificationAudience audience, NotificationEventType type,
                                                 NotificationContent content) {
        validateRefundSource(refund, attempt, audience, type);
        Booking booking = refund.getBooking();
        UUID recipient = audience == NotificationAudience.DRIVER ? booking.getDriver().getId() : booking.getFinancialOwnerId();
        var n = base(recipient, audience, type, CATEGORY_FINANCE,
                (type == NotificationEventType.REFUND_PENDING ? refund.getId() : attempt.getId()) + ":" + type,
                content);
        n.setBookingTarget(booking);
        n.refundId = refund.getId();
        if (audience == NotificationAudience.OWNER) {
            n.actionType = NotificationActionType.OPEN_REFUND;
            n.actionUrl = REFUND_PATH + n.refundId;
        }
        return n;
    }

    public static AppNotification responsibilityDecision(com.thang.chargeops.booking.decision.BookingServiceFailureDecision decision,
                                                         NotificationContent content) {
        if (decision.getId() == null) throw new AppException(NotificationErrorCode.INVALID_INTENT);
        var booking = decision.getBooking();
        var n = base(booking.getDriver().getId(), NotificationAudience.DRIVER,
                NotificationEventType.RESPONSIBILITY_DECIDED, CATEGORY_FINANCE,
                decision.getId() + ":RESPONSIBILITY_DECIDED", content);
        n.setBookingTarget(booking);
        return n;
    }

    public static AppNotification assignedTicket(SupportTicket ticket, NotificationAudience audience,
                                                   String sourceKey, NotificationContent content) {
        if (ticket.getId() == null || ticket.getAssignedHandler() == null
                || (audience != NotificationAudience.OWNER && audience != NotificationAudience.STAFF
                    && audience != NotificationAudience.ADMIN)
                || (audience == NotificationAudience.ADMIN) != (ticket.getStation() == null)) {
            throw new AppException(NotificationErrorCode.INVALID_INTENT);
        }
        var n = base(ticket.getAssignedHandler().getId(), audience, NotificationEventType.TICKET_ASSIGNED,
                CATEGORY_TICKET, sourceKey, content);
        n.setTicketTarget(ticket, NotificationActionType.OPEN_TICKET);
        return n;
    }

    public static AppNotification escalatedCase(TicketEscalation escalation, UUID adminId,
                                                 NotificationContent content) {
        if (escalation.getId() == null) throw new AppException(NotificationErrorCode.INVALID_INTENT);
        var n = base(adminId, NotificationAudience.ADMIN, NotificationEventType.CASE_ESCALATED,
                CATEGORY_TICKET, escalation.getId() + ":" + NotificationEventType.CASE_ESCALATED, content);
        n.escalationId = escalation.getId();
        n.setTicketTarget(escalation.getTicket(), NotificationActionType.OPEN_CASE);
        return n;
    }

    private static AppNotification base(UUID recipient, NotificationAudience audience, NotificationEventType type,
                                        String category, String key, NotificationContent content) {
        if (recipient == null || audience == null || type == null || content == null
                || key == null || key.isBlank() || key.length() > EVENT_KEY_MAX_LENGTH) {
            throw new AppException(NotificationErrorCode.INVALID_INTENT);
        }
        var n = new AppNotification();
        n.recipientId = recipient;
        n.audience = audience;
        n.eventType = type;
        n.category = category;
        n.eventKey = key;
        n.sourceEventKey = key;
        n.title = content.title();
        n.body = content.body();
        n.createdAt = content.createdAt();
        return n;
    }

    private void setTicketTarget(SupportTicket ticket, NotificationActionType action) {
        ticketId = ticket.getId();
        stationId = ticket.getStation() == null ? null : ticket.getStation().getId();
        bookingId = ticket.getBooking() == null ? null : ticket.getBooking().getId();
        actionType = action;
        actionUrl = TICKET_PATH + ticketId;
    }

    private void setBookingTarget(Booking booking) {
        bookingId = booking.getId();
        stationId = booking.getConnector().getChargePoint().getStation().getId();
        actionType = NotificationActionType.OPEN_BOOKING;
        actionUrl = BOOKING_PATH + bookingId;
    }

    private static boolean isBookingEvent(NotificationEventType type) {
        if (type == null) return false;
        return switch (type) {
            case BOOKING_CONFIRMED, BOOKING_CANCELLED, BOOKING_NO_SHOW, BOOKING_COMPLETED, BOOKING_REMINDER -> true;
            default -> false;
        };
    }

    private static void validateRefundSource(Refund refund, RefundAttempt attempt,
                                           NotificationAudience audience, NotificationEventType type) {
        if (refund.getId() == null || (audience != NotificationAudience.DRIVER && audience != NotificationAudience.OWNER)) {
            throw new AppException(NotificationErrorCode.INVALID_INTENT);
        }
        if (type == NotificationEventType.REFUND_PENDING) return;
        RefundAttemptStatus expectedStatus;
        if (type == NotificationEventType.REFUND_SUCCEEDED) {
            expectedStatus = RefundAttemptStatus.SUCCEEDED;
        } else if (type == NotificationEventType.REFUND_ATTEMPT_FAILED) {
            expectedStatus = RefundAttemptStatus.FAILED;
        } else {
            throw new AppException(NotificationErrorCode.UNSUPPORTED_EVENT);
        }
        if (attempt == null || attempt.getId() == null || !refund.getId().equals(attempt.getRefund().getId())
                || attempt.getStatus() != expectedStatus) {
            throw new AppException(NotificationErrorCode.INVALID_INTENT);
        }
    }
}
