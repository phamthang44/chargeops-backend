package com.thang.chargeops.notification.email;

import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.refund.model.RefundStatus;
import java.util.UUID;

/** Display fields only; no receipt, actor notes, JPA entity or bank reference reaches the template. */
public record BookingCancelledEmailModel(String recipientName, String recipientEmail, String bookingCode,
        UUID bookingId, String stationName, String stationAddress, String timeWindowText, String cancelledAtText,
        String refundText, String bookingUrl, String supportContact, String preheader, String reasonText) {

    public static BookingCancelledEmailModel cancelled(NotificationEmailDeliveryRepository.CancellationContext source,
                                                        String frontendUrl, String supportContact) {
        if (source.getBookingCode() == null || source.getBookingCode().isBlank()
                || source.getStationName() == null || source.getStationName().isBlank()
                || source.getStationAddress() == null || source.getStationAddress().isBlank()) {
            throw new IllegalArgumentException("Cancellation display snapshot is required");
        }
        String refundText;
        if (source.getRefundStatus() == RefundStatus.SUCCEEDED) {
            refundText = "Khoản hoàn mô phỏng " + EmailTextFormatter.formatVnd(source.getRefundAmount()) + " đã hoàn tất.";
        } else if (source.getRefundStatus() == RefundStatus.PENDING) {
            refundText = "Khoản hoàn " + EmailTextFormatter.formatVnd(source.getRefundAmount()) + " đang chờ xử lý (mô phỏng).";
        } else if (source.getRefundStatus() != null) {
            throw new IllegalArgumentException("Unsupported refund status");
        } else if (source.getPreviousStatus() == BookingStatus.PENDING) {
            refundText = "Booking bị hủy trước khi thanh toán; không phát sinh hoàn tiền.";
        } else {
            refundText = "Không phát sinh nghĩa vụ hoàn tiền cho lần hủy này.";
        }
        return new BookingCancelledEmailModel(EmailTextFormatter.safeRecipientName(source.getRecipientName(), source.getRecipientEmail()),
                source.getRecipientEmail(), source.getBookingCode(), source.getBookingId(), source.getStationName(),
                source.getStationAddress(), EmailTextFormatter.formatTimeWindow(source.getStartAt(), source.getEndAt())
                    + " (" + NotificationEmailConstants.TIME_ZONE_LABEL + ")",
                EmailTextFormatter.formatDateTime(source.getCancelledAt()), refundText,
                EmailTextFormatter.buildBookingUrl(frontendUrl, source.getBookingId()), supportContact,
                "Booking " + source.getBookingCode() + " đã bị hủy",
                "DRIVER_CANCELLED".equals(source.getCancellationReason()) ? "Bạn đã hủy booking"
                        : "Booking đã bị hủy do trạm không thể phục vụ");
    }

    public String subjectLine() { return "ChargeOps · Đã hủy booking " + bookingCode; }
}
