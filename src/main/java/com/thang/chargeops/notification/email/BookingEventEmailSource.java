package com.thang.chargeops.notification.email;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistory;
import com.thang.chargeops.booking.decision.BookingServiceFailureDecision;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.notification.kafka.*;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.notification.outbox.*;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.*;
import com.thang.chargeops.refund.model.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** Called only within delivery transactions. All source relationships are checked again before SMTP. */
@Service @RequiredArgsConstructor
public class BookingEventEmailSource {
    private final EntityManager em;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clocks;

    public Resolved resolve(NotificationOutbox o, String frontendUrl, String support) {
        var b = em.find(Booking.class, o.getBookingId());
        if (b == null) throw invalid();
        // Soft-deleted equipment is excluded by Hibernate; check before dereferencing lazy proxies.
        if (em.createQuery("SELECT count(b) FROM Booking b JOIN b.connector c JOIN c.chargePoint cp JOIN cp.station s WHERE b.id=:id AND s.id=:station AND c.deletedAt IS NULL AND cp.deletedAt IS NULL AND s.deletedAt IS NULL", Long.class)
                .setParameter("id", b.getId()).setParameter("station", o.getStationId()).getSingleResult() == 0)
            return new Resolved("RESOURCE_UNAVAILABLE", null);
        var type = NotificationEventType.valueOf(o.getEventType());
        String heading;
        String message;
        String stale = null;
        UUID refundId = null;
        switch (type) {
            case BOOKING_NO_SHOW, BOOKING_COMPLETED -> {
                var h = em.find(BookingStatusHistory.class, o.getSourceHistoryId());
                if (h == null || !h.getBooking().getId().equals(b.getId()) || !h.getOccurredAt().equals(o.getOccurredAt())
                        || !o.getSourceEventKey().equals(h.getId() + ":" + type)
                        || (type == NotificationEventType.BOOKING_NO_SHOW
                            ? h.getToStatus() != BookingStatus.CANCELLED || !"NO_SHOW".equals(h.getReason())
                            : h.getToStatus() != BookingStatus.COMPLETED)) throw invalid();
                heading = type == NotificationEventType.BOOKING_NO_SHOW ? "Booking quá hạn check-in" : "Booking đã hoàn tất";
                message = type == NotificationEventType.BOOKING_NO_SHOW
                        ? "Booking đã bị hủy vì không check-in đúng hạn. Sự kiện này không xác lập khoản hoàn tiền."
                        : "Lượt đặt chỗ đã kết thúc. Trạng thái này không xác nhận dung lượng pin hoặc số điện đã sạc.";
            }
            case RESPONSIBILITY_DECIDED -> {
                var d = em.find(BookingServiceFailureDecision.class, o.getSourceDecisionId());
                if (d == null || !d.getBooking().getId().equals(b.getId()) || !d.getDecidedAt().equals(o.getOccurredAt())
                        || !o.getSourceEventKey().equals(d.getId() + ":" + type)) throw invalid();
                heading = "Kết quả xem xét trách nhiệm booking";
                message = BookingEventOutboxWriter.decisionCopy(d);
            }
            case REFUND_PENDING, REFUND_ATTEMPT_FAILED, REFUND_SUCCEEDED -> {
                Refund r;
                if (type == NotificationEventType.REFUND_PENDING) {
                    r = em.find(Refund.class, o.getSourceRefundId());
                    if (r == null || !r.getDecisionAt().equals(o.getOccurredAt())
                            || !o.getSourceEventKey().equals(r.getId() + ":" + type)) throw invalid();
                } else {
                    var a = em.find(RefundAttempt.class, o.getSourceAttemptId());
                    if (a == null || !Objects.equals(a.getCompletedAt(), o.getOccurredAt())
                            || !o.getSourceEventKey().equals(a.getId() + ":" + type)
                            || a.getStatus() != (type == NotificationEventType.REFUND_SUCCEEDED ? RefundAttemptStatus.SUCCEEDED : RefundAttemptStatus.FAILED)) throw invalid();
                    r = a.getRefund();
                    if (type == NotificationEventType.REFUND_ATTEMPT_FAILED
                            && (!r.isRequiresOwnerAction() || r.getStatus() != RefundStatus.PENDING
                                || em.createQuery("SELECT count(a) FROM RefundAttempt a WHERE a.refund.id=:id AND a.sequenceNo>:sequence", Long.class)
                                    .setParameter("id", r.getId()).setParameter("sequence", a.getSequenceNo()).getSingleResult() > 0)) stale = "EVENT_SUPERSEDED";
                    if (type == NotificationEventType.REFUND_SUCCEEDED && (r.getStatus() != RefundStatus.SUCCEEDED
                            || r.getSuccessfulAttempt() == null || !r.getSuccessfulAttempt().getId().equals(a.getId()))) throw invalid();
                }
                if (!r.getBooking().getId().equals(b.getId()) || r.getPayment().getEnvironment() != PaymentEnvironment.SIMULATOR
                        || r.getSourcePaymentTransaction().getApplicationClassification() != PaymentApplicationClassification.APPLIED) throw invalid();
                refundId = r.getId();
                if (type == NotificationEventType.REFUND_PENDING && r.getStatus() != RefundStatus.PENDING) stale = "EVENT_SUPERSEDED";
                heading = type == NotificationEventType.REFUND_SUCCEEDED ? "Khoản hoàn mô phỏng đã hoàn tất"
                        : type == NotificationEventType.REFUND_PENDING ? "Khoản hoàn đang chờ xử lý" : "Thực thi khoản hoàn chưa thành công";
                message = switch (type) {
                    case REFUND_PENDING -> "Nghĩa vụ hoàn " + EmailTextFormatter.formatVnd(r.getAmount()) + " đang chờ thực thi trong Simulator.";
                    case REFUND_SUCCEEDED -> "Khoản hoàn " + EmailTextFormatter.formatVnd(r.getAmount()) + " đã hoàn tất trong Simulator; đây không phải chuyển tiền ngân hàng.";
                    default -> "Một lần thực thi khoản hoàn mô phỏng chưa thành công. Chủ trạm cần kiểm tra và thử lại trong hồ sơ khoản hoàn.";
                };
            }
            case BOOKING_REMINDER -> {
                if (o.getReminderStartAt() == null || !o.getSourceEventKey().equals(b.getId() + ":" + o.getReminderStartAt() + ":BOOKING_REMINDER")
                        || !o.getOccurredAt().isBefore(o.getReminderStartAt())
                        || o.getOccurredAt().isBefore(o.getReminderStartAt().minusSeconds(1800))) throw invalid();
                var payment = em.createQuery("SELECT p FROM Payment p WHERE p.booking.id=:id", com.thang.chargeops.payment.entity.Payment.class)
                        .setParameter("id", b.getId()).getResultStream().findFirst();
                if (!Objects.equals(b.getStartAt(), o.getReminderStartAt()) || b.getStatus() != BookingStatus.CONFIRMED
                        || b.getCheckedInAt() != null || b.getChargingStartedAt() != null || !clocks.getIfAvailable(Clock::systemUTC).instant().isBefore(b.getStartAt())
                        || payment.isEmpty() || payment.get().getStatus() != PaymentStatus.PAID || payment.get().isNeedsReconciliation()) stale = "REMINDER_NO_LONGER_ELIGIBLE";
                heading = "Nhắc lịch booking sắp bắt đầu";
                message = "Booking sắp tới giờ bắt đầu. Hãy mở chi tiết để kiểm tra trạng thái mới nhất và check-in đúng hạn.";
            }
            default -> throw invalid();
        }
        var recipient = em.find(UserProfile.class, o.getRecipientId());
        var connector = b.getConnector();
        var cp = connector.getChargePoint();
        var station = cp.getStation();
        String skip = recipient == null || recipient.getStatus() != UserStatus.ACTIVE || recipient.isDeleted() ? "RECIPIENT_INACTIVE"
                : connector.isDeleted() || cp.isDeleted() || station.isDeleted() || !station.getId().equals(o.getStationId()) ? "RESOURCE_UNAVAILABLE"
                : "DRIVER".equals(o.getAudience()) ? !b.getDriver().getId().equals(o.getRecipientId()) ? "RECIPIENT_SCOPE_CHANGED" : stale
                : !"OWNER".equals(o.getAudience()) || !Objects.equals(b.getFinancialOwnerId(), o.getRecipientId())
                    || !station.getOwner().getId().equals(o.getRecipientId()) || refundId == null ? "RECIPIENT_SCOPE_CHANGED" : stale;
        if (skip != null) return new Resolved(skip, null);
        String url = EmailTextFormatter.buildBookingUrl(frontendUrl, b.getId());
        if ("OWNER".equals(o.getAudience())) {
            String base = frontendUrl.replaceAll("/+$", "");
            url = base + "/owner/revenue?tab=refunds";
        }
        var model = new BookingEventEmailModel(EmailTextFormatter.safeRecipientName(recipient.getDisplayName(), recipient.getEmail()),
                recipient.getEmail(), b.getBookingCode(), b.getStationNameSnapshot(),
                EmailTextFormatter.formatTimeWindow(b.getStartAt(), b.getEndAt()) + " (" + NotificationEmailConstants.TIME_ZONE_LABEL + ")",
                EmailTextFormatter.formatDateTime(o.getOccurredAt()), heading, message, url, support, heading);
        return new Resolved(null, model);
    }

    private static InvalidBookingNotificationException invalid() { return new InvalidBookingNotificationException("UNTRUSTED_EVENT_SOURCE"); }
    public record Resolved(String skipReason, BookingEventEmailModel model) {}
}
