package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Shared, read-only payment/receipt/refund snapshot assembly for actor-specific details. */
@Component
@RequiredArgsConstructor
public class BookingFinancialReadAssembler {
    private static final String SEPAY_TEST_INSTRUCTION =
            "SePay Test Mode only — simulated payment; do not transfer real money.";

    private final DriverBookingReadPolicy driverBookingReadPolicy;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;

    public BookingReadSnapshot assemble(Booking booking, Payment payment, Instant evaluatedAt) {
        BookingReadSnapshot base = driverBookingReadPolicy.snapshotForList(booking, evaluatedAt);
        List<PaymentTransaction> receipts = payment.getId() == null ? List.of()
                : paymentTransactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId());
        List<Refund> refunds = booking.getId() == null ? List.of()
                : refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(booking.getId());
        return BookingReadSnapshot.builder()
                .evaluatedAt(base.evaluatedAt()).stationAvailable(base.stationAvailable())
                .canReportIssue(base.canReportIssue()).currency(payment.getCurrency())
                .collectedAmount(sum(receipts, null))
                .appliedToPackageAmount(sum(receipts, PaymentApplicationClassification.APPLIED))
                .packageRefundedAmount(amountOrZero(payment.getRefundAmount()))
                .excessAmount(0L)
                .unallocatedAmount(sum(receipts, PaymentApplicationClassification.UNAPPLIED))
                .checkout(checkout(booking, payment, evaluatedAt))
                .refunds(refunds.stream().map(this::refund).toList())
                .build();
    }

    private BookingDetailResponse.RefundSummary refund(Refund refund) {
        boolean reconcile = refund.getPayment() != null && refund.getPayment().isNeedsReconciliation();
        return new BookingDetailResponse.RefundSummary(refund.getId(), refund.getAmount().longValueExact(),
                BookingDetailResponse.RefundReason.valueOf(refund.getReason().name()),
                BookingDetailResponse.RefundState.valueOf(refund.getStatus().name()),
                refund.getExecutionPolicy(), refund.isRequiresAdminAction(), reconcile);
    }

    private long sum(List<PaymentTransaction> receipts, PaymentApplicationClassification classification) {
        return receipts.stream().filter(r -> classification == null || r.getApplicationClassification() == classification)
                .map(PaymentTransaction::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).longValueExact();
    }

    private long amountOrZero(BigDecimal amount) { return amount == null ? 0L : amount.longValueExact(); }

    private BookingDetailResponse.CheckoutDetail checkout(Booking booking, Payment payment, Instant at) {
        BookingDetailResponse.CheckoutState state = getCheckoutState(booking, payment, at);
        boolean actionable = state == BookingDetailResponse.CheckoutState.READY;

        return new BookingDetailResponse.CheckoutDetail(
                state,
                payment.getMethod(),
                payment.getProviderExpiresAt(),
                checkoutInstruction(payment, actionable),
                actionable ? payment.getProviderOrderRef() : null,
                actionable ? payment.getQrCodeUrl() : null
        );
    }

    private static BookingDetailResponse.CheckoutState getCheckoutState(
            Booking booking,
            Payment payment,
            Instant at
    ) {
        boolean payable = booking.getStatus() == BookingStatus.PENDING
                && booking.getExpiresAt() != null
                && at.isBefore(booking.getExpiresAt())
                && payment.getStatus() == PaymentStatus.PENDING;

        if (!payable) {
            return BookingDetailResponse.CheckoutState.UNAVAILABLE;
        }
        if (payment.getProviderOrderRef() == null) {
            return BookingDetailResponse.CheckoutState.NOT_CREATED;
        }
        if (payment.getProviderExpiresAt() == null) {
            return BookingDetailResponse.CheckoutState.UNAVAILABLE;
        }
        if (!at.isBefore(payment.getProviderExpiresAt())) {
            return BookingDetailResponse.CheckoutState.EXPIRED;
        }
        return BookingDetailResponse.CheckoutState.READY;
    }

    private String checkoutInstruction(Payment payment, boolean actionable) {
        if (!actionable) {
            return null;
        }
        if (payment.getMethod() == PaymentMethod.SIMULATOR) {
            return "Complete payment in the simulator before the hold expires.";
        }
        if (payment.getMethod() == PaymentMethod.BANK_TRANSFER
                && "SEPAY".equals(payment.getProvider())) {
            return SEPAY_TEST_INSTRUCTION;
        }
        return null;
    }
}
