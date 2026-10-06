package com.thang.chargeops.payment.service.impl;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.dto.response.OwnerFinanceBookingResponse;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.dto.response.OwnerFinanceSummaryResponse;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.OwnerFinanceService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OwnerFinanceServiceImpl implements OwnerFinanceService {
    private final CurrentProfileProvider currentProfileProvider;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRepository refundRepository;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OwnerFinanceSummaryResponse summary() {
        UUID ownerId = currentProfileProvider.requireProfileId();
        long driftedCount = paymentRepository.countOwnerSimulatorDriftedPayments(ownerId);
        if (driftedCount > 0) {
            Map<String, Object> details = Map.of(
                    "ownerId", ownerId,
                    "driftType", "ITEM_LEVEL_DISCREPANCY",
                    "driftedPaymentCount", driftedCount
            );
            throw AppException.withDetails(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED, details);
        }
        var totals = paymentRepository.summarizeOwnerSimulatorLedger(ownerId);
        long gross = transactionRepository.sumOwnerAppliedSimulatorReceipts(ownerId).longValueExact();
        long refunded = refundRepository.sumOwnerSucceededSimulatorRefunds(ownerId).longValueExact();
        long pending = refundRepository.sumOwnerPendingSimulatorRefunds(ownerId).longValueExact();
        return new OwnerFinanceSummaryResponse(gross, refunded, gross - refunded,
                pending, totals.getPaymentCount(), totals.getPaidCount());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<OwnerFinanceBookingResponse> list(int pageNo, int pageSize) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        Page<Payment> payments = paymentRepository.findOwnerSimulatorLedger(ownerId,
                PageRequest.of(pageNo - 1, pageSize,
                        Sort.by(Sort.Order.desc("paidAt"), Sort.Order.desc("id"))));
        List<UUID> bookingIds = payments.getContent().stream()
                .map(payment -> payment.getBooking().getId()).toList();
        List<UUID> paymentIds = payments.getContent().stream()
                .map(Payment::getId).toList();

        Map<UUID, Long> appliedReceiptsByPaymentId = paymentIds.isEmpty() ? Map.of()
                : transactionRepository.findByPaymentIdInAndApplicationClassification(
                        paymentIds, PaymentApplicationClassification.APPLIED).stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getPayment().getId(),
                        Collectors.summingLong(tx -> tx.getAmount().longValueExact())));

        Map<UUID, Refund> refunds = bookingIds.isEmpty() ? Map.of()
                : refundRepository.findByBookingIdInOrderByCreatedAtAscIdAsc(bookingIds).stream()
                .collect(Collectors.toMap(refund -> refund.getBooking().getId(), Function.identity(),
                        (first, second) -> second));

        return payments.map(payment -> {
            long collected = appliedReceiptsByPaymentId.getOrDefault(payment.getId(), 0L);
            Refund refund = refunds.get(payment.getBooking().getId());
            return response(payment, refund, List.of(), collected);
        });
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OwnerFinanceBookingResponse get(UUID bookingId) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        Payment payment = paymentRepository.findOwnerSimulatorBookingPayment(ownerId, bookingId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Refund refund = refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)
                .stream().findFirst().orElse(null);
        List<OwnerFinanceBookingResponse.Receipt> receipts = transactionRepository
                .findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId()).stream()
                .filter(tx -> tx.getApplicationClassification() == PaymentApplicationClassification.APPLIED)
                .map(this::receipt).toList();
        long collected = receipts.stream().mapToLong(OwnerFinanceBookingResponse.Receipt::amount).sum();
        return response(payment, refund, receipts, collected);
    }

    private OwnerFinanceBookingResponse response(Payment payment, Refund refund,
                                                  List<OwnerFinanceBookingResponse.Receipt> receipts,
                                                  long collected) {
        var booking = payment.getBooking();
        var station = booking.getConnector().getChargePoint().getStation();

        long mirrorCollected = payment.getAmount() == null ? 0L : payment.getAmount().longValueExact();
        long mirrorRefunded = payment.getRefundAmount() == null ? 0L : payment.getRefundAmount().longValueExact();
        long sourceRefunded = (refund != null && refund.getStatus() == RefundStatus.SUCCEEDED)
                ? refund.getAmount().longValueExact() : 0L;

        if (payment.getPaidAt() == null || collected != mirrorCollected || sourceRefunded != mirrorRefunded) {
            Map<String, Object> details = Map.of(
                    "paymentId", payment.getId(),
                    "bookingId", booking.getId(),
                    "sourceCollected", collected,
                    "mirrorCollected", mirrorCollected,
                    "sourceRefunded", sourceRefunded,
                    "mirrorRefunded", mirrorRefunded,
                    "paidAtNull", payment.getPaidAt() == null
            );
            throw AppException.withDetails(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED, details);
        }

        long pending = (refund != null && refund.getStatus() == RefundStatus.PENDING)
                ? refund.getAmount().longValueExact() : 0L;

        return new OwnerFinanceBookingResponse(booking.getId(), booking.getBookingCode(),
                station.getId(), station.getName(), payment.getStatus(), collected, sourceRefunded,
                pending, collected - sourceRefunded, refund == null ? null : refund.getStatus(),
                payment.getPaidAt(), receipts);
    }

    private OwnerFinanceBookingResponse.Receipt receipt(PaymentTransaction tx) {
        return new OwnerFinanceBookingResponse.Receipt(tx.getId(), tx.getTransactionRef(),
                tx.getAmount().longValueExact(), tx.getReceivedAt());
    }
}
