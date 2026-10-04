package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.exception.errorcode.*;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.RefundStatus;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;
import static com.thang.chargeops.exception.errorcode.ServiceFailureErrorCode.*;

/** Preview and command use the same side-effect-free persisted-fact checks. */
@Component
public class ServiceFailureEligibilityPolicy {
    public BaseErrorCode cancel(Booking booking, Instant now) {
        if (booking.getStatus() == BookingStatus.CHECKED_IN || booking.getStatus() == BookingStatus.CHARGING)
            return OPERATIONAL_RESOLUTION_REQUIRED;
        if (booking.getCheckedInAt() != null || booking.getChargingStartedAt() != null) return RECONCILIATION_REQUIRED;
        if (booking.getStatus() == BookingStatus.PENDING) {
            if (booking.getExpiresAt() == null) return RECONCILIATION_REQUIRED;
            return now.isBefore(booking.getExpiresAt()) ? null : BookingErrorCode.HOLD_EXPIRED;
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            if (booking.getCheckInDeadline() == null) return RECONCILIATION_REQUIRED;
            return now.isBefore(booking.getCheckInDeadline()) ? null : BookingErrorCode.CHECK_IN_CLOSED;
        }
        return BookingErrorCode.STATE_CONFLICT;
    }

    public BaseErrorCode admission(Booking booking, Instant now) {
        if (booking.getStatus() == BookingStatus.CHECKED_IN || booking.getStatus() == BookingStatus.CHARGING)
            return OPERATIONAL_RESOLUTION_REQUIRED;
        if (booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.CANCELLED) return null;
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            if (booking.getCheckInDeadline() == null || booking.getCheckedInAt() != null || booking.getChargingStartedAt() != null)
                return RECONCILIATION_REQUIRED;
            return now.isBefore(booking.getCheckInDeadline()) ? USE_OWNER_CANCEL_COMMAND : null;
        }
        return NO_APPLIED_PAYMENT;
    }

    public BaseErrorCode financial(Booking booking, Payment payment, PaymentTransaction source, Refund refund) {
        if (payment.getEnvironment() != PaymentEnvironment.SIMULATOR) return OUT_OF_DEMO_SCOPE;
        if (payment.getStatus() != PaymentStatus.PAID && payment.getStatus() != PaymentStatus.REFUNDED)
            return NO_APPLIED_PAYMENT;
        if (payment.isNeedsReconciliation() || payment.getPaidAt() == null || payment.getPaymentCode() == null
                || !Objects.equals(payment.getBooking().getId(), booking.getId())
                || payment.getAmount() == null || booking.getTotalAmount() == null
                || payment.getAmount().signum() <= 0 || payment.getAmount().stripTrailingZeros().scale() > 0
                || payment.getAmount().compareTo(booking.getTotalAmount()) != 0 || !"VND".equals(payment.getCurrency())
                || source == null || source.getPayment() == null
                || !Objects.equals(source.getPayment().getId(), payment.getId())
                || source.getApplicationClassification() != PaymentApplicationClassification.APPLIED
                || source.getAmount() == null || source.getAmount().compareTo(payment.getAmount()) != 0
                || !Objects.equals(source.getCurrency(), payment.getCurrency())) return RECONCILIATION_REQUIRED;
        if (refund == null) {
            return payment.getStatus() == PaymentStatus.PAID
                    && (payment.getRefundAmount() == null || payment.getRefundAmount().signum() == 0) ? null : RECONCILIATION_REQUIRED;
        }
        boolean pending = payment.getStatus() == PaymentStatus.PAID && refund.getStatus() == RefundStatus.PENDING
                && (payment.getRefundAmount() == null || payment.getRefundAmount().signum() == 0);
        boolean succeeded = payment.getStatus() == PaymentStatus.REFUNDED && refund.getStatus() == RefundStatus.SUCCEEDED
                && payment.getRefundAmount() != null && payment.getRefundAmount().compareTo(payment.getAmount()) == 0;
        if (!(pending || succeeded) || !Objects.equals(refund.getBooking().getId(), booking.getId())
                || !Objects.equals(refund.getPayment().getId(), payment.getId())
                || !Objects.equals(refund.getSourcePaymentTransaction().getId(), source.getId())
                || refund.getAmount().compareTo(payment.getAmount()) != 0 || !Objects.equals(refund.getCurrency(), payment.getCurrency()))
            return RECONCILIATION_REQUIRED;
        return null;
    }
}
