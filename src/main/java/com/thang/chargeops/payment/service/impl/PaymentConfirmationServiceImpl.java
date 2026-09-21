package com.thang.chargeops.payment.service.impl;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentApplicationReason;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.projection.OrderPaymentMatchProjection;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

        LockedPaymentContext context = lockPaymentContext(match.projection());

        // A concurrent webhook for the same Payment waits on the locks above.
        // Check again after the wait so it sees the transaction committed by the first request.
        duplicate = findDuplicate(receipt);
        if (duplicate.isPresent()) return PaymentReceiptResult.duplicate(duplicate.get());

        Instant acceptedAt = applicationClock.instant();
        Optional<PaymentApplicationReason> rejection = rejectionReason(context, receipt, acceptedAt);
        return rejection
                .map(reason -> saveUnapplied(context.payment(), receipt, reason))
                .orElseGet(() -> applyReceipt(context, receipt, acceptedAt));
    }

    private void validateReceiptIdentity(NormalizedReceipt receipt) {
        if (receipt == null) {
            throw new AppException(PaymentErrorCode.RECEIPT_INVALID);
        }
        requireText(receipt.provider());
        requireText(receipt.receivingAccountRef());
        requireText(receipt.transactionRef());
    }

    private Optional<PaymentTransaction> findDuplicate(NormalizedReceipt receipt) {
        return paymentTransactionRepository.findByProviderAndReceivingAccountRefAndTransactionRef(
                receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef());
    }

    /** Both order code and VA must identify the same Payment without loading entities before the lock. */
    private PaymentMatch matchExactOrder(NormalizedReceipt receipt) {
        if (isBlank(receipt.paymentCode()) || isBlank(receipt.vaNumber())) {
            return PaymentMatch.unmatched(PaymentApplicationReason.INCOMPLETE_ORDER_IDENTITY);
        }
        Optional<OrderPaymentMatchProjection> byCode =
                paymentRepository.findOrderPaymentMatchByPaymentCode(receipt.paymentCode().trim());
        Optional<OrderPaymentMatchProjection> byVa =
                paymentRepository.findOrderPaymentMatchByVaNumber(receipt.vaNumber().trim());
        if (byCode.isEmpty() || byVa.isEmpty()) {
            return PaymentMatch.unmatched(PaymentApplicationReason.UNMATCHED);
        }
        if (!byCode.get().getPaymentId().equals(byVa.get().getPaymentId())) {
            return PaymentMatch.unmatched(PaymentApplicationReason.ORDER_IDENTITY_MISMATCH);
        }
        return PaymentMatch.matched(byCode.get());
    }

    private LockedPaymentContext lockPaymentContext(OrderPaymentMatchProjection match) {
        Connector connector = connectorRepository.findByIdWithLock(match.getConnectorId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Booking booking = bookingRepository.findByIdWithLock(match.getBookingId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = paymentRepository.findByIdWithLock(match.getPaymentId())
                .orElseThrow(() -> new AppException(PaymentErrorCode.NOT_FOUND));
        return new LockedPaymentContext(connector, booking, payment);
    }

    private Optional<PaymentApplicationReason> rejectionReason(
            LockedPaymentContext context, NormalizedReceipt receipt, Instant now) {
        Payment payment = context.payment();
        Booking booking = context.booking();

        // 1. ALREADY_PAID
        if (payment.getStatus() == PaymentStatus.PAID) {
            return Optional.of(PaymentApplicationReason.ALREADY_PAID);
        }

        // 2. LATE: overdue hold or already marked expired
        if (booking.getStatus() == BookingStatus.EXPIRED
                || booking.getExpiresAt() == null || !now.isBefore(booking.getExpiresAt())
                || payment.getProviderExpiresAt() != null && !now.isBefore(payment.getProviderExpiresAt())) {
            return Optional.of(PaymentApplicationReason.LATE);
        }

        // 3. BOOKING_NOT_PENDING
        if (booking.getStatus() != BookingStatus.PENDING) {
            return Optional.of(PaymentApplicationReason.BOOKING_NOT_PENDING);
        }

        // 4. MERCHANT_IDENTITY_MISMATCH
        if (!payment.getProvider().equals(receipt.provider())
                || !payment.getReceivingAccountRef().equals(receipt.receivingAccountRef())
                || !payment.getCurrency().equals(receipt.currency())) {
            return Optional.of(PaymentApplicationReason.MERCHANT_IDENTITY_MISMATCH);
        }

        // 5. NO_ACTIVE_ORDER
        if (payment.getProviderOrderRef() == null || payment.getVaNumber() == null) {
            return Optional.of(PaymentApplicationReason.NO_ACTIVE_ORDER);
        }

        // 6. UNDERPAYMENT / OVERPAYMENT
        int amountComparison = receipt.amount().compareTo(payment.getAmount());
        if (amountComparison < 0) return Optional.of(PaymentApplicationReason.UNDERPAYMENT);
        if (amountComparison > 0) return Optional.of(PaymentApplicationReason.OVERPAYMENT);

        // 7. STATION_UNAVAILABLE: Check positive operational conditions for station/charge point/connector
        Connector conn = context.connector();
        ChargePoint cp = conn.getChargePoint();
        Station station = cp != null ? cp.getStation() : null;
        boolean serviceable = station != null
                && station.getStatus() == StationStatus.ACTIVE
                && station.getOperationalStatus() == StationOperationalStatus.OPERATING
                && cp.getProvisioningStatus() == ProvisioningStatus.ACTIVE
                && cp.getOperationalChargePointStatus() == OperationalChargePointStatus.AVAILABLE
                && (conn.getRuntimeStatus() == RuntimeStatus.AVAILABLE || conn.getRuntimeStatus() == RuntimeStatus.IN_USE);
        if (!serviceable) {
            return Optional.of(PaymentApplicationReason.STATION_UNAVAILABLE);
        }

        return Optional.empty();
    }

    private PaymentReceiptResult saveUnmatched(NormalizedReceipt receipt, PaymentApplicationReason reason) {
        log.warn("Unmatched receipt: provider={}, account={}, txRef={}, reason={}",
                receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef(), reason);
        PaymentTransaction transaction = PaymentTransaction.create(null, receipt);
        transaction.noteUnapplied(reason.name());
        paymentTransactionRepository.save(transaction);
        return PaymentReceiptResult.unmatched(transaction);
    }

    private PaymentReceiptResult saveUnapplied(Payment payment, NormalizedReceipt receipt, PaymentApplicationReason reason) {
        log.info("Receipt not applied: paymentId={}, txRef={}, reason={}",
                payment.getId(), receipt.transactionRef(), reason);
        PaymentTransaction transaction = PaymentTransaction.create(payment, receipt);
        transaction.noteUnapplied(reason.name());
        paymentTransactionRepository.save(transaction);
        return PaymentReceiptResult.unapplied(transaction, reason.name());
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

    private void requireText(String value) {
        if (isBlank(value)) throw new AppException(PaymentErrorCode.RECEIPT_INVALID);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record LockedPaymentContext(Connector connector, Booking booking, Payment payment) {
    }

    private record PaymentMatch(OrderPaymentMatchProjection projection, PaymentApplicationReason reason) {
        private static PaymentMatch matched(OrderPaymentMatchProjection projection) {
            return new PaymentMatch(projection, null);
        }
        private static PaymentMatch unmatched(PaymentApplicationReason reason) {
            return new PaymentMatch(null, reason);
        }
        private boolean matched() {
            return projection != null;
        }
    }
}
