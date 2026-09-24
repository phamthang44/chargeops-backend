package com.thang.chargeops.refund.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.model.PendingRefundSpec;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.model.RefundStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A durable obligation to return one accepted full-package payment. */
@Entity
@Table(name = "refunds", uniqueConstraints = {
        @UniqueConstraint(name = "ux_refunds_source_transaction", columnNames = "source_payment_transaction_id"),
        @UniqueConstraint(name = "ux_refunds_basis", columnNames = {"basis_type", "basis_id"})
}, indexes = {
        @Index(name = "idx_refunds_status_decision", columnList = "status, decision_at, id"),
        @Index(name = "idx_refunds_booking", columnList = "booking_id"),
        @Index(name = "idx_refunds_payment", columnList = "payment_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_payment_transaction_id", nullable = false, updatable = false)
    private PaymentTransaction sourcePaymentTransaction;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis_type", nullable = false, length = 40, updatable = false)
    private RefundBasisType basisType;

    @Column(name = "basis_id", nullable = false, updatable = false)
    private UUID basisId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "decision_at", nullable = false, updatable = false)
    private Instant decisionAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_by", nullable = false, updatable = false)
    private UserProfile decidedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "successful_attempt_id")
    private RefundAttempt successfulAttempt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public static Refund createPending(PendingRefundSpec spec) {
        if (spec == null || spec.booking() == null || spec.payment() == null
                || spec.sourcePaymentTransaction() == null || spec.reason() == null
                || spec.basisType() == null || spec.basisId() == null
                || spec.decisionAt() == null || spec.decidedBy() == null) {
            throw executionConflict("Complete server refund decision data is required");
        }

        Payment payment = spec.payment();
        PaymentTransaction source = spec.sourcePaymentTransaction();
        if (!sameEntity(spec.booking(), payment.getBooking())
                || !sameEntity(payment, source.getPayment())
                || source.getApplicationClassification() != PaymentApplicationClassification.APPLIED
                || payment.getStatus() != PaymentStatus.PAID) {
            throw executionConflict("Refund source must be the accepted receipt for the paid booking");
        }

        BigDecimal amount = exactWholeVnd(payment.getAmount());
        if (!"VND".equals(payment.getCurrency())
                || !"VND".equals(source.getCurrency())
                || source.getAmount() == null
                || amount.compareTo(source.getAmount()) != 0) {
            throw amountConflict("Refund must equal the accepted full-package VND receipt");
        }

        Refund refund = new Refund();
        refund.booking = spec.booking();
        refund.payment = payment;
        refund.sourcePaymentTransaction = source;
        refund.amount = amount;
        refund.currency = "VND";
        refund.reason = spec.reason();
        refund.basisType = spec.basisType();
        refund.basisId = spec.basisId();
        refund.status = RefundStatus.PENDING;
        refund.decisionAt = spec.decisionAt();
        refund.decidedBy = spec.decidedBy();
        return refund;
    }

    /** Marks the obligation complete only from a successful attempt owned by this Refund. */
    public void completeWith(RefundAttempt attempt, Instant completedAt) {
        if (attempt == null || !sameEntity(this, attempt.getRefund()) || !attempt.isSucceeded()
                || completedAt == null || completedAt.isBefore(decisionAt)) {
            throw executionConflict("A successful attempt owned by this refund is required");
        }
        if (status == RefundStatus.SUCCEEDED) {
            if (sameEntity(successfulAttempt, attempt)) {
                return;
            }
            throw executionConflict("Refund already has a different successful attempt");
        }
        this.successfulAttempt = attempt;
        this.completedAt = completedAt;
        this.status = RefundStatus.SUCCEEDED;
    }

    private static BigDecimal exactWholeVnd(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw amountConflict("Refund amount must be positive");
        }
        try {
            return value.setScale(0, RoundingMode.UNNECESSARY).setScale(2);
        } catch (ArithmeticException exception) {
            throw amountConflict("Refund amount must be whole VND");
        }
    }

    private static boolean sameEntity(Object left, Object right) {
        if (left == right) {
            return left != null;
        }
        if (left instanceof com.thang.chargeops.common.entity.BaseEntity leftEntity
                && right instanceof com.thang.chargeops.common.entity.BaseEntity rightEntity) {
            return leftEntity.getId() != null && Objects.equals(leftEntity.getId(), rightEntity.getId());
        }
        return false;
    }

    private static AppException executionConflict(String message) {
        return new AppException(RefundErrorCode.EXECUTION_CONFLICT, message);
    }

    private static AppException amountConflict(String message) {
        return new AppException(RefundErrorCode.AMOUNT_CONFLICT, message);
    }
}
