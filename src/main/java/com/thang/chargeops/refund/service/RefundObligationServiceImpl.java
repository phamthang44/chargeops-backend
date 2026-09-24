package com.thang.chargeops.refund.service;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;
import com.thang.chargeops.refund.model.PendingRefundSpec;
import com.thang.chargeops.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefundObligationServiceImpl implements RefundObligationService {
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRepository refundRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund createObligation(CreateRefundObligationCommand command) {
        requireComplete(command);
        Payment payment = command.lockedPayment();
        if (!Objects.equals(command.lockedBooking().getId(), payment.getBooking().getId())) {
            throw conflict("Payment does not belong to the locked booking");
        }

        // This row serializes all claims on one receipt. Do not query Refund first.
        PaymentTransaction source = transactionRepository.findByIdWithLock(command.sourceReceiptId())
                .orElseThrow(() -> conflict("Source receipt does not exist"));
        if (source.getPayment() == null || !Objects.equals(payment.getId(), source.getPayment().getId())
                || source.getApplicationClassification() != PaymentApplicationClassification.APPLIED) {
            throw conflict("Source is not an applied receipt for this payment");
        }
        if (payment.getEnvironment() == null || payment.getEnvironment() == PaymentEnvironment.LEGACY
                || payment.isNeedsReconciliation() || payment.getPaymentCode() == null) {
            throw conflict("Historical or unresolved payment requires reconciliation");
        }

        Optional<Refund> sameBasis = refundRepository.findByBasisTypeAndBasisId(
                command.basisType(), command.basisId());
        if (sameBasis.isPresent()) {
            Refund existing = refundRepository.findByIdWithLock(sameBasis.orElseThrow().getId())
                    .orElseThrow(() -> conflict("Refund basis disappeared"));
            if (sameDecision(existing, command, source)) {
                return existing;
            }
            throw conflict("Refund basis already belongs to a different decision");
        }
        if (refundRepository.findBySourcePaymentTransactionId(source.getId()).isPresent()) {
            throw conflict("Source receipt already has a refund obligation");
        }
        // Payment is already caller-locked, so two different receipts cannot claim
        // separate full-package obligations for the same payment through this service.
        if (refundRepository.existsByPaymentId(payment.getId())) {
            throw conflict("Payment already has a full-package refund obligation");
        }
        if (payment.getStatus() != PaymentStatus.PAID || payment.getPaidAt() == null
                || (payment.getRefundAmount() != null && payment.getRefundAmount().signum() != 0)) {
            throw conflict("Payment is not eligible for a new full-package refund");
        }

        Refund pending = Refund.createPending(new PendingRefundSpec(
                command.lockedBooking(), payment, source, command.reason(), command.basisType(),
                command.basisId(), command.decisionAt(), command.lockedActor()));
        try {
            return refundRepository.saveAndFlush(pending);
        } catch (DataIntegrityViolationException exception) {
            if (isRefundUniquenessConflict(exception)) {
                throw conflict("Refund source or basis was claimed concurrently");
            }
            throw exception;
        }
    }

    private static boolean sameDecision(Refund existing, CreateRefundObligationCommand command,
                                        PaymentTransaction source) {
        Payment payment = command.lockedPayment();
        BigDecimal amount = payment.getAmount();
        return Objects.equals(existing.getBooking().getId(), command.lockedBooking().getId())
                && Objects.equals(existing.getPayment().getId(), payment.getId())
                && Objects.equals(existing.getSourcePaymentTransaction().getId(), source.getId())
                && existing.getBasisType() == command.basisType()
                && Objects.equals(existing.getBasisId(), command.basisId())
                && existing.getReason() == command.reason()
                && amount != null && existing.getAmount().compareTo(amount) == 0
                && Objects.equals(existing.getCurrency(), payment.getCurrency())
                && amount.compareTo(source.getAmount()) == 0
                && Objects.equals(existing.getCurrency(), source.getCurrency());
    }

    private static void requireComplete(CreateRefundObligationCommand command) {
        if (command == null || command.lockedBooking() == null || command.lockedBooking().getId() == null
                || command.lockedPayment() == null || command.lockedPayment().getId() == null
                || command.sourceReceiptId() == null || command.basisType() == null
                || command.basisId() == null || command.reason() == null
                || command.lockedActor() == null || command.lockedActor().getId() == null
                || command.decisionAt() == null) {
            throw conflict("Complete server-owned refund decision is required");
        }
    }

    private static boolean isRefundUniquenessConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if ("ux_refunds_basis".equals(name) || "ux_refunds_source_transaction".equals(name)) {
                    return true;
                }
            }
            if (current instanceof SQLException sql && !"23505".equals(sql.getSQLState())) {
                return false;
            }
        }
        return false;
    }

    private static AppException conflict(String detail) {
        return new AppException(RefundErrorCode.EXECUTION_CONFLICT, detail);
    }
}
