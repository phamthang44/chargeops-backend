package com.thang.chargeops.payment.service.impl;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.payment.dto.response.OwnerFinanceBookingResponse;
import com.thang.chargeops.payment.entity.Payment;
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
    @Transactional(readOnly = true)
    public Page<OwnerFinanceBookingResponse> list(int pageNo, int pageSize) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        Page<Payment> payments = paymentRepository.findOwnerSimulatorLedger(ownerId,
                PageRequest.of(pageNo - 1, pageSize,
                        Sort.by(Sort.Order.desc("paidAt"), Sort.Order.desc("id"))));
        List<UUID> bookingIds = payments.getContent().stream()
                .map(payment -> payment.getBooking().getId()).toList();
        Map<UUID, Refund> refunds = bookingIds.isEmpty() ? Map.of()
                : refundRepository.findByBookingIdInOrderByCreatedAtAscIdAsc(bookingIds).stream()
                .collect(Collectors.toMap(refund -> refund.getBooking().getId(), Function.identity(),
                        (first, second) -> second));
        return payments.map(payment -> response(payment,
                refunds.get(payment.getBooking().getId()), List.of()));
    }

    @Override
    @Transactional(readOnly = true)
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
        return response(payment, refund, receipts);
    }

    private OwnerFinanceBookingResponse response(Payment payment, Refund refund,
                                                  List<OwnerFinanceBookingResponse.Receipt> receipts) {
        var booking = payment.getBooking();
        var station = booking.getConnector().getChargePoint().getStation();
        long collected = payment.getPaidAt() == null ? 0 : payment.getAmount().longValueExact();
        long refunded = payment.getRefundAmount() == null ? 0 : payment.getRefundAmount().longValueExact();
        long pending = refund != null && refund.getStatus() == RefundStatus.PENDING
                ? refund.getAmount().longValueExact() : 0;
        return new OwnerFinanceBookingResponse(booking.getId(), booking.getBookingCode(),
                station.getId(), station.getName(), payment.getStatus(), collected, refunded,
                pending, collected - refunded, refund == null ? null : refund.getStatus(),
                payment.getPaidAt(), receipts);
    }

    private OwnerFinanceBookingResponse.Receipt receipt(PaymentTransaction tx) {
        return new OwnerFinanceBookingResponse.Receipt(tx.getId(), tx.getTransactionRef(),
                tx.getAmount().longValueExact(), tx.getReceivedAt());
    }
}
