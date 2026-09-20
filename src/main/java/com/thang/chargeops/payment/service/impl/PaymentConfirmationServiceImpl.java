package com.thang.chargeops.payment.service.impl;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentConfirmationServiceImpl implements PaymentConfirmationService {

    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final BookingPolicyConfig bookingPolicyConfig;
    private final Clock applicationClock;

    @Override
    @Transactional
    public PaymentReceiptResult processReceipt(NormalizedReceipt receipt) {
        validateReceiptIdentity(receipt);

        Optional<PaymentTransaction> duplicate = findDuplicate(receipt);
        if (duplicate.isPresent()) return PaymentReceiptResult.duplicate(duplicate.get());

        PaymentMatch match = matchExactOrder(receipt);
        if (!match.matched()) return saveUnmatched(receipt, match.reason());

        LockedPaymentContext context = lockPaymentContext(match.payment());

        // A concurrent webhook for the same Payment waits on the locks above.
        // Check again after the wait so it sees the transaction committed by the first request.
        duplicate = findDuplicate(receipt);
        if (duplicate.isPresent()) return PaymentReceiptResult.duplicate(duplicate.get());

        Instant acceptedAt = applicationClock.instant();
        Optional<String> rejection = rejectionReason(context, receipt, acceptedAt);
        return rejection.map(s -> saveUnapplied(context.payment(), receipt, s)).orElseGet(() -> applyReceipt(context, receipt, acceptedAt));
    }

    private void validateReceiptIdentity(NormalizedReceipt receipt) {
        Objects.requireNonNull(receipt, "Receipt must not be null");
        requireText(receipt.provider(), "provider");
        requireText(receipt.receivingAccountRef(), "receivingAccountRef");
        requireText(receipt.transactionRef(), "transactionRef");
    }

    private Optional<PaymentTransaction> findDuplicate(NormalizedReceipt receipt) {
        return paymentTransactionRepository.findByProviderAndReceivingAccountRefAndTransactionRef(
                receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef());
    }

    /** Both order code and VA must identify the same Payment; neither is synthesized. */
    private PaymentMatch matchExactOrder(NormalizedReceipt receipt) {
        if (isBlank(receipt.paymentCode()) || isBlank(receipt.vaNumber())) {
            return PaymentMatch.unmatched("INCOMPLETE_ORDER_IDENTITY");
        }
        Optional<Payment> byCode = paymentRepository.findByPaymentCode(receipt.paymentCode().trim());
        Optional<Payment> byVa = paymentRepository.findByVaNumber(receipt.vaNumber().trim());
        if (byCode.isEmpty() || byVa.isEmpty()) return PaymentMatch.unmatched("UNMATCHED");
        if (!samePayment(byCode.get(), byVa.get())) {
            return PaymentMatch.unmatched("ORDER_IDENTITY_MISMATCH");
        }
        return PaymentMatch.matched(byCode.get());
    }

    private LockedPaymentContext lockPaymentContext(Payment matchedPayment) {
        UUID bookingId = matchedPayment.getBooking().getId();
        UUID connectorId = matchedPayment.getBooking().getConnector().getId();
        connectorRepository.findByIdWithLock(connectorId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Booking booking = bookingRepository.findByIdWithLock(bookingId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = paymentRepository.findByBookingIdWithLock(bookingId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.NOT_FOUND));
        return new LockedPaymentContext(booking, payment);
    }

    private Optional<String> rejectionReason(
            LockedPaymentContext context, NormalizedReceipt receipt, Instant now) {
        Payment payment = context.payment();
        Booking booking = context.booking();
        if (payment.getStatus() == PaymentStatus.PAID) return Optional.of("ALREADY_PAID");
        if (booking.getStatus() != BookingStatus.PENDING) return Optional.of("BOOKING_NOT_PENDING");
        if (booking.getExpiresAt() == null || !now.isBefore(booking.getExpiresAt())
                || payment.getProviderExpiresAt() != null && !now.isBefore(payment.getProviderExpiresAt())) {
            return Optional.of("LATE");
        }
        if (!payment.getProvider().equals(receipt.provider())
                || !payment.getReceivingAccountRef().equals(receipt.receivingAccountRef())
                || !payment.getCurrency().equals(receipt.currency())) {
            return Optional.of("MERCHANT_IDENTITY_MISMATCH");
        }
        if (payment.getProviderOrderRef() == null || payment.getVaNumber() == null) {
            return Optional.of("NO_ACTIVE_ORDER");
        }
        int amountComparison = receipt.amount().compareTo(payment.getAmount());
        if (amountComparison < 0) return Optional.of("UNDERPAYMENT");
        if (amountComparison > 0) return Optional.of("OVERPAYMENT");
        return Optional.empty();
    }

    private PaymentReceiptResult saveUnmatched(NormalizedReceipt receipt, String reason) {
        log.warn("Unmatched receipt: provider={}, account={}, txRef={}, reason={}",
                receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef(), reason);
        PaymentTransaction transaction = PaymentTransaction.create(null, receipt);
        transaction.noteUnapplied(reason);
        paymentTransactionRepository.save(transaction);
        return PaymentReceiptResult.unmatched(transaction);
    }

    private PaymentReceiptResult saveUnapplied(Payment payment, NormalizedReceipt receipt, String reason) {
        log.info("Receipt not applied: paymentId={}, txRef={}, reason={}",
                payment.getId(), receipt.transactionRef(), reason);
        PaymentTransaction transaction = PaymentTransaction.create(payment, receipt);
        transaction.noteUnapplied(reason);
        paymentTransactionRepository.save(transaction);
        return PaymentReceiptResult.unapplied(transaction, reason);
    }

    private PaymentReceiptResult applyReceipt(
            LockedPaymentContext context, NormalizedReceipt receipt, Instant acceptedAt) {
        PaymentTransaction transaction = PaymentTransaction.create(context.payment(), receipt);
        context.payment().acceptReceipt(transaction, acceptedAt);
        paymentTransactionRepository.save(transaction);

        Instant graceDeadline = acceptedAt.plus(
                Duration.ofMinutes(bookingPolicyConfig.getCancellationGraceMinutes()));
        Instant freeCancellationDeadline = graceDeadline.isBefore(context.booking().getStartAt())
                ? graceDeadline : context.booking().getStartAt();
        bookingStatusHistoryRecorder.recordSystemTransition(
                context.booking(), BookingStatusReason.PAYMENT_CONFIRMED, acceptedAt,
                booking -> booking.confirmPayment(acceptedAt, freeCancellationDeadline));

        log.info("Receipt applied: txRef={}, bookingId={}, paymentId={}",
                receipt.transactionRef(), context.booking().getId(), context.payment().getId());
        return PaymentReceiptResult.applied(transaction, context.payment(), context.booking());
    }

    private boolean samePayment(Payment left, Payment right) {
        return left == right || left.getId() != null && left.getId().equals(right.getId());
    }

    private void requireText(String value, String field) {
        if (isBlank(value)) throw new IllegalArgumentException(field + " must not be blank");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record LockedPaymentContext(Booking booking, Payment payment) {
    }

    private record PaymentMatch(Payment payment, String reason) {
        private static PaymentMatch matched(Payment payment) { return new PaymentMatch(payment, null); }
        private static PaymentMatch unmatched(String reason) { return new PaymentMatch(null, reason); }
        private boolean matched() { return payment != null; }
    }
}
