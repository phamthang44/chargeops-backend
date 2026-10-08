package com.thang.chargeops.notification.outbox;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistory;
import com.thang.chargeops.booking.decision.BookingServiceFailureDecision;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.kafka.BookingNotificationEvent;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.refund.entity.*;
import com.thang.chargeops.refund.model.RefundAttemptStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Sources are persisted in the caller's domain transaction; network work is deferred. */
@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BookingEventOutboxWriter {
    private final AppNotificationRepository notices;
    private final NotificationOutboxRepository outbox;
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

    public void recordHistory(BookingStatusHistory history) {
        var type = history.getToStatus() == BookingStatus.COMPLETED ? NotificationEventType.BOOKING_COMPLETED
                : history.getToStatus() == BookingStatus.CANCELLED && "NO_SHOW".equals(history.getReason())
                    ? NotificationEventType.BOOKING_NO_SHOW : null;
        if (type == null) return;
        var b = history.getBooking();
        var notice = AppNotification.driverBooking(b, type, history.getId() + ":" + type,
                content(type == NotificationEventType.BOOKING_NO_SHOW ? NotificationI18nKey.BOOKING_NO_SHOW_TITLE
                        : NotificationI18nKey.BOOKING_COMPLETED_TITLE, b, history.getOccurredAt()), null);
        enqueue(b, notice, history.getId(), null, null, null, null);
    }

    public void recordDecision(BookingServiceFailureDecision decision) {
        var notice = AppNotification.responsibilityDecision(decision,
                new NotificationContent("Kết quả xem xét trách nhiệm booking",
                        "Booking " + decision.getBooking().getBookingCode() + ": " + decisionCopy(decision), decision.getDecidedAt()));
        enqueue(decision.getBooking(), notice, null, decision.getId(), null, null, null);
    }

    public static String decisionCopy(BookingServiceFailureDecision d) {
        return switch (d.getResult()) {
            case FULL_REFUND -> "Đã chấp thuận hoàn đủ giá gói; xem trạng thái khoản hoàn trong booking.";
            case NO_APPLIED_PAYMENT -> "Trạm nhận trách nhiệm; chưa có khoản thanh toán hợp lệ để hoàn.";
            case INSUFFICIENT_EVIDENCE -> "Chưa đủ căn cứ áp dụng chính sách hoàn tiền.";
        };
    }

    public void recordPending(Refund refund) {
        var notice = AppNotification.refundOutcome(refund, null, NotificationAudience.DRIVER, NotificationEventType.REFUND_PENDING,
                content(NotificationI18nKey.REFUND_PENDING_TITLE, refund.getBooking(), refund.getDecisionAt()));
        enqueue(refund.getBooking(), notice, null, null, refund.getId(), null, null);
    }

    public void recordAttempt(RefundAttempt attempt) {
        var refund = attempt.getRefund();
        boolean succeeded = attempt.getStatus() == RefundAttemptStatus.SUCCEEDED;
        if (!succeeded && attempt.getStatus() != RefundAttemptStatus.FAILED) throw new IllegalArgumentException("Terminal attempt required");
        var type = succeeded ? NotificationEventType.REFUND_SUCCEEDED : NotificationEventType.REFUND_ATTEMPT_FAILED;
        var audiences = succeeded ? List.of(NotificationAudience.DRIVER, NotificationAudience.OWNER) : List.of(NotificationAudience.OWNER);
        for (var audience : audiences) {
            var notice = AppNotification.refundOutcome(refund, attempt, audience, type,
                    content(succeeded ? NotificationI18nKey.REFUND_SUCCEEDED_TITLE : NotificationI18nKey.REFUND_ATTEMPT_FAILED_TITLE,
                            refund.getBooking(), attempt.getCompletedAt()));
            enqueue(refund.getBooking(), notice, null, null, null, attempt.getId(), null);
        }
    }

    public void recordReminder(Booking booking, Instant now) {
        var key = booking.getId() + ":" + booking.getStartAt() + ":BOOKING_REMINDER";
        var notice = AppNotification.driverBooking(booking, NotificationEventType.BOOKING_REMINDER, key,
                content(NotificationI18nKey.BOOKING_REMINDER_TITLE, booking, now), booking.getStartAt());
        enqueue(booking, notice, null, null, null, null, booking.getStartAt());
    }

    private static NotificationContent content(String title, Booking b, Instant at) {
        return new NotificationContent(title, NotificationI18nKey.format(NotificationI18nKey.BOOKING_STATE_MESSAGE,
                Map.of("code", b.getBookingCode() == null ? b.getId().toString() : b.getBookingCode())), at);
    }

    private void enqueue(Booking b, AppNotification notice, UUID historyId, UUID decisionId,
                         UUID refundId, UUID attemptId, Instant reminderStartAt) {
        notices.insertBookingIntentIfAbsent(notice);
        if (outbox.existsBySourceEventKeyAndRecipientIdAndAudienceAndChannel(notice.getSourceEventKey(),
                notice.getRecipientId(), notice.getAudience().name(), "EMAIL")) return;
        var event = new BookingNotificationEvent(UUID.randomUUID(), 1, notice.getEventType(), notice.getSourceEventKey(),
                b.getId(), notice.getStationId(), notice.getRecipientId(), notice.getAudience(), notice.getCreatedAt());
        outbox.save(NotificationOutbox.sourced(event, json.writeValueAsString(event), historyId, decisionId, refundId, attemptId, reminderStartAt));
    }
}
