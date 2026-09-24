package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.BookingMapper;
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

/**
 * Shared assembler for Driver-facing booking details.
 * Used by read services and cancellation services to ensure identical projection
 * and capability evaluations, including persisted refunds.
 */
@Component
@RequiredArgsConstructor
public class DriverBookingDetailAssembler {

    private static final String SEPAY_TEST_INSTRUCTION =
            "SePay Test Mode only — simulated payment; do not transfer real money.";

    private final DriverBookingReadPolicy driverBookingReadPolicy;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final BookingMapper bookingMapper;

    public BookingDetailResponse assemble(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(payment, "payment must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");

        BookingReadSnapshot snapshot = buildBookingReadSnapshot(booking, payment, evaluatedAt);
        return bookingMapper.toBookingDetailResponse(booking, payment, snapshot);
    }

    public BookingReadSnapshot buildBookingReadSnapshot(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        BookingReadSnapshot listSnapshot = driverBookingReadPolicy
                .snapshotForList(booking, evaluatedAt);

        List<PaymentTransaction> receipts = payment.getId() != null
                ? paymentTransactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId())
                : List.of();

        List<Refund> refundEntities = booking.getId() != null
                ? refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(booking.getId())
                : List.of();

        List<BookingDetailResponse.RefundSummary> refundSummaries = refundEntities.stream()
                .map(this::toRefundSummary)
                .toList();

        return BookingReadSnapshot.builder()
                .evaluatedAt(listSnapshot.evaluatedAt())
                .stationAvailable(listSnapshot.stationAvailable())
                .canReportIssue(listSnapshot.canReportIssue())
                .currency(payment.getCurrency())
                .collectedAmount(sumReceiptAmounts(receipts, null))
                .appliedToPackageAmount(sumReceiptAmounts(
                        receipts,
                        PaymentApplicationClassification.APPLIED
                ))
                .packageRefundedAmount(amountOrZero(payment.getRefundAmount()))
                .excessAmount(0L)
                .unallocatedAmount(sumReceiptAmounts(
                        receipts,
                        PaymentApplicationClassification.UNAPPLIED
                ))
                .checkout(toCheckoutDetail(booking, payment, evaluatedAt))
                .refunds(refundSummaries)
                .build();
    }

    private BookingDetailResponse.RefundSummary toRefundSummary(Refund refund) {
        boolean needsReconciliation = refund.getPayment() != null && refund.getPayment().isNeedsReconciliation();
        return new BookingDetailResponse.RefundSummary(
                refund.getId(),
                refund.getAmount().longValueExact(),
                BookingDetailResponse.RefundReason.valueOf(refund.getReason().name()),
                BookingDetailResponse.RefundState.valueOf(refund.getStatus().name()),
                needsReconciliation
        );
    }

    private long sumReceiptAmounts(
            List<PaymentTransaction> receipts,
            PaymentApplicationClassification classification
    ) {
        return receipts.stream()
                .filter(receipt -> classification == null
                        || receipt.getApplicationClassification() == classification)
                .map(PaymentTransaction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .longValueExact();
    }

    private long amountOrZero(BigDecimal amount) {
        return amount == null ? 0L : amount.longValueExact();
    }

    private BookingDetailResponse.CheckoutDetail toCheckoutDetail(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        BookingDetailResponse.CheckoutState state;
        boolean bookingCanStillBePaid = booking.getStatus() == BookingStatus.PENDING
                && booking.getExpiresAt() != null
                && evaluatedAt.isBefore(booking.getExpiresAt())
                && payment.getStatus() == PaymentStatus.PENDING;

        if (!bookingCanStillBePaid) {
            state = BookingDetailResponse.CheckoutState.UNAVAILABLE;
        } else if (payment.getProviderOrderRef() == null) {
            state = BookingDetailResponse.CheckoutState.NOT_CREATED;
        } else if (payment.getProviderExpiresAt() == null) {
            state = BookingDetailResponse.CheckoutState.UNAVAILABLE;
        } else if (!evaluatedAt.isBefore(payment.getProviderExpiresAt())) {
            state = BookingDetailResponse.CheckoutState.EXPIRED;
        } else {
            state = BookingDetailResponse.CheckoutState.READY;
        }

        boolean checkoutIsActionable = state == BookingDetailResponse.CheckoutState.READY;
        return new BookingDetailResponse.CheckoutDetail(
                state,
                payment.getMethod(),
                payment.getProviderExpiresAt(),
                checkoutIsActionable && payment.getMethod() == PaymentMethod.SIMULATOR
                        ? "Complete payment in the simulator before the hold expires."
                        : checkoutIsActionable && payment.getMethod() == PaymentMethod.BANK_TRANSFER
                                && "SEPAY".equals(payment.getProvider())
                                ? SEPAY_TEST_INSTRUCTION
                                : null,
                checkoutIsActionable ? payment.getProviderOrderRef() : null,
                checkoutIsActionable ? payment.getQrCodeUrl() : null
        );
    }
}
