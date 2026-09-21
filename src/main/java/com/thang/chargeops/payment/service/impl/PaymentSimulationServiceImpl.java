package com.thang.chargeops.payment.service.impl;

import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.dto.request.SimulationRequest;
import com.thang.chargeops.payment.dto.response.SimulationResultResponse;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentApplicationReason;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.payment.service.PaymentSimulationService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentSimulationServiceImpl implements PaymentSimulationService {

    private static final String SIMULATOR_INSTRUCTION =
            "Complete payment in the simulator before the hold expires.";
    private static final String SEPAY_TEST_INSTRUCTION =
            "SePay Test Mode only — simulated payment; do not transfer real money.";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentConfirmationService paymentConfirmationService;
    private final BookingCommandInFlightLock bookingCommandInFlightLock;
    private final BookingCommandRegistry bookingCommandRegistry;
    private final CurrentProfileProvider currentProfileProvider;
    private final BookingMapper bookingMapper;
    private final DriverBookingReadPolicy driverBookingReadPolicy;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final Clock applicationClock;

    @Override
    @Transactional
    public SimulationResultResponse simulate(
            UUID bookingId,
            UUID requestKey,
            SimulationRequest request
    ) {
        if (bookingId == null || requestKey == null || request == null) {
            throw new AppException(PaymentErrorCode.SIMULATION_REQUEST_INVALID);
        }

        UserProfile actor = currentProfileProvider.requireProfile();
        return bookingCommandInFlightLock.executeWithLock(
                actor.getId(),
                BookingCommandOperation.SIMULATE_PAYMENT,
                requestKey,
                () -> executeSimulation(actor, bookingId, requestKey, request)
        );
    }

    private SimulationResultResponse executeSimulation(
            UserProfile actor,
            UUID bookingId,
            UUID requestKey,
            SimulationRequest request
    ) {
        String payloadHash = hashRequest(bookingId, request);

        Optional<UUID> replay = bookingCommandRegistry.findReplay(
                actor.getId(),
                BookingCommandOperation.SIMULATE_PAYMENT,
                requestKey,
                payloadHash
        );
        if (replay.isPresent()) {
            return buildReplayResponse(replay.get(), request);
        }

        return switch (request.outcome()) {
            case TIMEOUT -> handleTimeout(bookingId);
            case FAILED -> handleFailure(actor, bookingId, requestKey, payloadHash, request);
            case SUCCESS -> handleSuccess(actor, bookingId, requestKey, payloadHash, request);
        };
    }

    private SimulationResultResponse handleTimeout(UUID bookingId) {
        requireSimulationContext(bookingId);
        log.warn("Payment simulation TIMEOUT simulated: throwing CHECKOUT_UNAVAILABLE");
        throw new AppException(PaymentErrorCode.CHECKOUT_UNAVAILABLE);
    }

    private SimulationResultResponse handleFailure(
            UserProfile actor,
            UUID bookingId,
            UUID requestKey,
            String payloadHash,
            SimulationRequest request
    ) {
        SimulationContext context = requireSimulationContext(bookingId);
        Booking booking = context.booking();
        Payment payment = context.payment();

        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.markFailed();
            paymentRepository.save(payment);
        }

        Instant now = applicationClock.instant();
        bookingCommandRegistry.recordSuccess(
                actor,
                BookingCommandOperation.SIMULATE_PAYMENT,
                requestKey,
                payloadHash,
                booking,
                now
        );

        BookingDetailResponse detail = buildBookingDetailResponse(booking, payment, now);

        return new SimulationResultResponse(
                SimulationRequest.Outcome.FAILED,
                false,
                detail,
                null
        );
    }

    private SimulationResultResponse handleSuccess(
            UserProfile actor,
            UUID bookingId,
            UUID requestKey,
            String payloadHash,
            SimulationRequest request
    ) {
        SimulationContext context = requireSimulationContext(bookingId);
        Booking booking = context.booking();
        Payment payment = context.payment();
        if (request.amount() <= 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID);
        }

        Instant receivedAt = applicationClock.instant();

        NormalizedReceipt receipt = new NormalizedReceipt(
                payment.getProvider(),
                payment.getReceivingAccountRef(),
                request.transactionRef(),
                BigDecimal.valueOf(request.amount()),
                request.currency(),
                request.providerPaidAt(),
                receivedAt,
                payment.getVaNumber(),
                payment.getPaymentCode(),
                "SIMULATED " + payment.getPaymentCode(),
                """
                {"source":"ADMIN_PAYMENT_SIMULATOR"}
                """
        );

        PaymentReceiptResult result = paymentConfirmationService.processReceipt(receipt);

        bookingCommandRegistry.recordSuccess(
                actor,
                BookingCommandOperation.SIMULATE_PAYMENT,
                requestKey,
                payloadHash,
                booking,
                receivedAt
        );

        PaymentTransaction transaction = paymentTransactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        payment.getProvider(),
                        payment.getReceivingAccountRef(),
                        request.transactionRef()
                )
                .orElseThrow(() -> new AppException(CommonErrorCode.INTERNAL_ERROR));

        BookingDetailResponse detail = buildBookingDetailResponse(booking, payment, receivedAt);

        boolean duplicateEvent = (result.status() == PaymentReceiptResult.Status.DUPLICATE);

        return new SimulationResultResponse(
                SimulationRequest.Outcome.SUCCESS,
                duplicateEvent,
                detail,
                toReceiptResponse(transaction, payment, booking)
        );
    }

    private SimulationResultResponse buildReplayResponse(
            UUID bookingId,
            SimulationRequest request
    ) {
        SimulationContext context = requireSimulationContext(bookingId);
        Booking booking = context.booking();
        Payment payment = context.payment();

        Instant now = applicationClock.instant();
        BookingDetailResponse detail = buildBookingDetailResponse(booking, payment, now);

        if (request.outcome() == SimulationRequest.Outcome.FAILED) {
            return new SimulationResultResponse(
                    SimulationRequest.Outcome.FAILED,
                    true,
                    detail,
                    null
            );
        }
        if (request.outcome() == SimulationRequest.Outcome.TIMEOUT) {
            throw new AppException(PaymentErrorCode.CHECKOUT_UNAVAILABLE);
        }

        Optional<PaymentTransaction> txOpt = paymentTransactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        payment.getProvider(),
                        payment.getReceivingAccountRef(),
                        request.transactionRef()
                );

        SimulationResultResponse.Receipt receipt = txOpt
                .map(tx -> toReceiptResponse(tx, payment, booking))
                .orElse(null);

        return new SimulationResultResponse(
                SimulationRequest.Outcome.SUCCESS,
                true,
                detail,
                receipt
        );
    }

    private BookingDetailResponse buildBookingDetailResponse(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        BookingReadSnapshot listSnapshot = driverBookingReadPolicy.snapshotForList(booking, evaluatedAt);
        List<PaymentTransaction> receipts = payment.getId() != null
                ? paymentTransactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId())
                : List.of();

        long collected = sumReceiptAmounts(receipts, null);
        long applied = sumReceiptAmounts(receipts, PaymentApplicationClassification.APPLIED);
        long unapplied = sumReceiptAmounts(receipts, PaymentApplicationClassification.UNAPPLIED);
        long refunded = payment.getRefundAmount() != null ? payment.getRefundAmount().longValue() : 0L;

        BookingReadSnapshot detailSnapshot = BookingReadSnapshot.builder()
                .evaluatedAt(listSnapshot.evaluatedAt())
                .stationAvailable(listSnapshot.stationAvailable())
                .canReportIssue(listSnapshot.canReportIssue())
                .currency(payment.getCurrency())
                .collectedAmount(collected)
                .appliedToPackageAmount(applied)
                .packageRefundedAmount(refunded)
                .excessAmount(0L)
                .unallocatedAmount(unapplied)
                .checkout(toCheckoutDetail(payment, evaluatedAt))
                .refunds(List.of())
                .build();

        return bookingMapper.toBookingDetailResponse(booking, payment, detailSnapshot);
    }

    private SimulationContext requireSimulationContext(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.NOT_FOUND));
        if (payment.getProviderOrderRef() == null
                || payment.getPaymentCode() == null
                || payment.getVaNumber() == null) {
            throw new AppException(PaymentErrorCode.CHECKOUT_REQUIRED);
        }
        return new SimulationContext(booking, payment);
    }

    private SimulationResultResponse.Receipt toReceiptResponse(
            PaymentTransaction transaction,
            Payment payment,
            Booking booking
    ) {
        SimulationResultResponse.ReceiptClassification classification =
                receiptClassification(transaction);
        SimulationResultResponse.ReconciliationStatus reconciliationStatus =
                transaction.getApplicationClassification() == PaymentApplicationClassification.APPLIED
                        ? SimulationResultResponse.ReconciliationStatus.RESOLVED
                        : SimulationResultResponse.ReconciliationStatus.OPEN;

        return new SimulationResultResponse.Receipt(
                transaction.getId(),
                payment.getId(),
                booking.getId(),
                transaction.getProvider(),
                transaction.getReceivingAccountRef(),
                transaction.getTransactionRef(),
                transaction.getAmount().longValueExact(),
                transaction.getCurrency(),
                transaction.getReceivedAt(),
                transaction.getProviderPaidAt(),
                classification,
                reconciliationStatus,
                0L
        );
    }

    private SimulationResultResponse.ReceiptClassification receiptClassification(
            PaymentTransaction transaction
    ) {
        if (transaction.getApplicationClassification() == PaymentApplicationClassification.APPLIED) {
            return SimulationResultResponse.ReceiptClassification.APPLIED;
        }
        PaymentApplicationReason reason = parseApplicationReason(transaction.getApplicationReason());
        return switch (reason) {
            case ALREADY_PAID -> SimulationResultResponse.ReceiptClassification.DUPLICATE_PACKAGE_PAYMENT;
            case LATE -> SimulationResultResponse.ReceiptClassification.LATE;
            case UNDERPAYMENT -> SimulationResultResponse.ReceiptClassification.UNDERPAID;
            case OVERPAYMENT -> SimulationResultResponse.ReceiptClassification.OVERPAID;
            default -> SimulationResultResponse.ReceiptClassification.UNMATCHED;
        };
    }

    private PaymentApplicationReason parseApplicationReason(String applicationReason) {
        if (applicationReason == null || applicationReason.isBlank()) {
            return PaymentApplicationReason.UNMATCHED;
        }
        try {
            return PaymentApplicationReason.valueOf(applicationReason);
        } catch (IllegalArgumentException ex) {
            return PaymentApplicationReason.UNMATCHED;
        }
    }

    private BookingDetailResponse.CheckoutDetail toCheckoutDetail(
            Payment payment,
            Instant evaluatedAt
    ) {
        BookingDetailResponse.CheckoutState state;
        if (payment.getProviderOrderRef() == null) {
            state = BookingDetailResponse.CheckoutState.NOT_CREATED;
        } else if (payment.getProviderExpiresAt() == null) {
            state = BookingDetailResponse.CheckoutState.UNAVAILABLE;
        } else if (!evaluatedAt.isBefore(payment.getProviderExpiresAt())) {
            state = BookingDetailResponse.CheckoutState.EXPIRED;
        } else {
            state = BookingDetailResponse.CheckoutState.READY;
        }

        return new BookingDetailResponse.CheckoutDetail(
                state,
                payment.getMethod(),
                payment.getProviderExpiresAt(),
                payment.getMethod() == PaymentMethod.SIMULATOR
                        ? SIMULATOR_INSTRUCTION
                        : payment.getMethod() == PaymentMethod.BANK_TRANSFER
                                && "SEPAY".equals(payment.getProvider())
                                ? SEPAY_TEST_INSTRUCTION
                                : null,
                payment.getProviderOrderRef(),
                payment.getQrCodeUrl()
        );
    }

    private long sumReceiptAmounts(
            List<PaymentTransaction> receipts,
            PaymentApplicationClassification classification
    ) {
        return receipts.stream()
                .filter(r -> classification == null || r.getApplicationClassification() == classification)
                .map(PaymentTransaction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .longValueExact();
    }

    private String hashRequest(UUID bookingId, SimulationRequest request) {
        String raw = String.join("\n",
                "simulate-payment-v1",
                "bookingId:" + bookingId,
                "outcome:" + request.outcome(),
                "transactionRef:" + request.transactionRef(),
                "amount:" + request.amount(),
                "currency:" + request.currency(),
                "providerPaidAt:" + request.providerPaidAt()
        );
        return BookingCommandPayloadHasher.sha256(raw);
    }

    private record SimulationContext(Booking booking, Payment payment) {
    }
}
