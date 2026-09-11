package com.thang.chargeops.payment.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.regex.Pattern;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends AuditableEntity {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 30)
    private PaymentMethod method;

    @Column(name = "gateway_txn_ref")
    private String gatewayTxnRef;

    @Column(name = "refund_amount", precision = 15, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "provider", length = 30)
    private String provider;

    @Column(name = "receiving_account_ref", length = 255)
    private String receivingAccountRef;

    @Column(name = "currency", length = 3)
    private String currency;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "collected_amount", precision = 19, scale = 2)
    private BigDecimal collectedAmount;

    @Column(name = "applied_to_package_amount", precision = 19, scale = 2)
    private BigDecimal appliedToPackageAmount;

    @Column(name = "package_refunded_amount", precision = 19, scale = 2)
    private BigDecimal packageRefundedAmount;

    @Column(name = "excess_amount", precision = 19, scale = 2)
    private BigDecimal excessAmount;

    @Column(name = "unallocated_amount", precision = 19, scale = 2)
    private BigDecimal unallocatedAmount;

    @Column(name = "needs_reconciliation", nullable = false)
    private boolean needsReconciliation;

    /**
     * Factory for creating a new pending Payment for a Booking.
     */
    public static Payment createPending(PendingPaymentSpec spec) {
        if (spec == null) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Payment spec must not be null");
        }
        if (spec.booking() == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Booking is required for Payment");
        }
        if (spec.amount() == null || spec.amount().compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Payment amount must not be negative");
        }
        BigDecimal normalizedAmount;
        try {
            normalizedAmount = spec.amount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Payment amount cannot have more than 2 decimal places");
        }
        if (spec.currency() == null || !CURRENCY_PATTERN.matcher(spec.currency().trim()).matches()) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Currency must be 3 uppercase alphabetic characters");
        }
        if (spec.provider() == null || spec.provider().isBlank() || spec.provider().length() > 30) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Provider must be 1 to 30 characters");
        }
        if (spec.receivingAccountRef() == null || spec.receivingAccountRef().isBlank() || spec.receivingAccountRef().length() > 255) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receiving account reference must be 1 to 255 characters");
        }
        if (spec.method() == null) {
            throw new AppException(PaymentErrorCode.METHOD_INVALID, "Payment method is required");
        }

        Payment payment = new Payment();
        payment.booking = spec.booking();
        payment.amount = normalizedAmount;
        payment.status = PaymentStatus.PENDING;
        payment.method = spec.method();
        payment.provider = spec.provider().trim();
        payment.receivingAccountRef = spec.receivingAccountRef().trim();
        payment.currency = spec.currency().trim();
        payment.version = 0L;
        payment.collectedAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        payment.appliedToPackageAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        payment.packageRefundedAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        payment.excessAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        payment.unallocatedAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        payment.needsReconciliation = false;
        return payment;
    }

    /**
     * Records unallocated incoming money from a receipt into projections.
     * Atomically increases collectedAmount and unallocatedAmount. Does NOT set PAID.
     */
    public void recordUnallocatedReceipt(BigDecimal receiptAmount) {
        ensureProjectionsReconciled();
        if (receiptAmount == null || receiptAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt amount must be strictly positive");
        }
        BigDecimal normalizedReceipt;
        try {
            normalizedReceipt = receiptAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt amount cannot have more than 2 decimal places");
        }

        BigDecimal candidateCollected = this.collectedAmount.add(normalizedReceipt);
        BigDecimal candidateUnallocated = this.unallocatedAmount.add(normalizedReceipt);

        validateProjectionValues(candidateCollected, this.appliedToPackageAmount, this.packageRefundedAmount, this.excessAmount, candidateUnallocated);

        this.collectedAmount = candidateCollected;
        this.unallocatedAmount = candidateUnallocated;
    }

    /**
     * Applies exact package amount from unallocated funds to cover the package.
     * Transitions status to PAID and sets paidAt timestamp.
     */
    public void applyPackagePayment(BigDecimal exactPackageAmount, Instant acceptedAt) {
        ensureProjectionsReconciled();
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.FAILED) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot apply payment to package in state " + this.status);
        }
        if (this.appliedToPackageAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Package has already been applied");
        }
        if (exactPackageAmount == null || exactPackageAmount.compareTo(this.amount) != 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Applied amount must exactly equal package price " + this.amount);
        }
        BigDecimal normalizedAmount;
        try {
            normalizedAmount = exactPackageAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Applied amount cannot have more than 2 decimal places");
        }
        if (this.unallocatedAmount.compareTo(normalizedAmount) < 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Insufficient unallocated funds to apply package");
        }
        if (acceptedAt == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Accepted timestamp must not be null");
        }

        BigDecimal candidateUnallocated = this.unallocatedAmount.subtract(normalizedAmount);
        BigDecimal candidateApplied = normalizedAmount;

        validateProjectionValues(this.collectedAmount, candidateApplied, this.packageRefundedAmount, this.excessAmount, candidateUnallocated);

        this.appliedToPackageAmount = candidateApplied;
        this.unallocatedAmount = candidateUnallocated;
        this.status = PaymentStatus.PAID;
        if (this.paidAt == null) {
            this.paidAt = acceptedAt;
        }
    }

    /**
     * Classifies a portion of unallocated money as excess money (e.g. overpayment or second receipt).
     * Does NOT increase collectedAmount again and does NOT change package payment status.
     */
    public void classifyExcess(BigDecimal excessToAdd) {
        ensureProjectionsReconciled();
        if (excessToAdd == null || excessToAdd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Excess amount must be strictly positive");
        }
        BigDecimal normalizedExcess;
        try {
            normalizedExcess = excessToAdd.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Excess amount cannot have more than 2 decimal places");
        }
        if (this.unallocatedAmount.compareTo(normalizedExcess) < 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Insufficient unallocated funds to classify as excess");
        }

        BigDecimal candidateUnallocated = this.unallocatedAmount.subtract(normalizedExcess);
        BigDecimal candidateExcess = this.excessAmount.add(normalizedExcess);

        validateProjectionValues(this.collectedAmount, this.appliedToPackageAmount, this.packageRefundedAmount, candidateExcess, candidateUnallocated);

        this.unallocatedAmount = candidateUnallocated;
        this.excessAmount = candidateExcess;
    }

    /**
     * Records cumulative successful package refund total from application service.
     * Replay of same total is idempotent no-op.
     * Decreasing total or exceeding appliedToPackageAmount is rejected.
     */
    public void recordCumulativePackageRefund(BigDecimal cumulativeTotal) {
        ensureProjectionsReconciled();
        if (this.appliedToPackageAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot record package refund when no package payment has been applied");
        }
        if (cumulativeTotal == null || cumulativeTotal.compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Cumulative refund amount must be non-negative");
        }
        BigDecimal normalizedTotal;
        try {
            normalizedTotal = cumulativeTotal.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Cumulative refund cannot have more than 2 decimal places");
        }

        if (normalizedTotal.compareTo(this.packageRefundedAmount) == 0) {
            return;
        }
        if (normalizedTotal.compareTo(this.packageRefundedAmount) < 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Cumulative package refund total cannot decrease");
        }
        if (normalizedTotal.compareTo(this.appliedToPackageAmount) > 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Cumulative package refund cannot exceed applied package amount");
        }

        validateProjectionValues(this.collectedAmount, this.appliedToPackageAmount, normalizedTotal, this.excessAmount, this.unallocatedAmount);

        this.packageRefundedAmount = normalizedTotal;
        if (normalizedTotal.compareTo(this.appliedToPackageAmount) == 0) {
            this.status = PaymentStatus.REFUNDED;
        } else {
            this.status = PaymentStatus.PARTIALLY_REFUNDED;
        }
    }

    /**
     * Marks payment as FAILED (e.g. from hold expiration or failure webhook).
     * Cannot downgrade PAID or REFUNDED payment.
     */
    public void markFailed() {
        if (this.status == PaymentStatus.PAID || this.status == PaymentStatus.PARTIALLY_REFUNDED || this.status == PaymentStatus.REFUNDED) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot transition paid or refunded payment to FAILED");
        }
        this.status = PaymentStatus.FAILED;
    }

    private void ensureProjectionsReconciled() {
        if (this.needsReconciliation || this.collectedAmount == null || this.appliedToPackageAmount == null
                || this.packageRefundedAmount == null || this.excessAmount == null || this.unallocatedAmount == null) {
            throw new AppException(PaymentErrorCode.RECONCILIATION_REQUIRED,
                    "Payment requires reconciliation before financial mutations");
        }
    }

    private void validateProjectionValues(
            BigDecimal candidateCollected,
            BigDecimal candidateApplied,
            BigDecimal candidatePackageRefunded,
            BigDecimal candidateExcess,
            BigDecimal candidateUnallocated
    ) {
        if (candidateCollected == null || candidateApplied == null || candidatePackageRefunded == null
                || candidateExcess == null || candidateUnallocated == null) {
            throw new AppException(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED, "Projection values must all be non-null");
        }
        if (candidateCollected.compareTo(BigDecimal.ZERO) < 0
                || candidateApplied.compareTo(BigDecimal.ZERO) < 0
                || candidatePackageRefunded.compareTo(BigDecimal.ZERO) < 0
                || candidateExcess.compareTo(BigDecimal.ZERO) < 0
                || candidateUnallocated.compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED, "Projection amounts must be non-negative");
        }
        if (candidateApplied.compareTo(this.amount) > 0) {
            throw new AppException(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED, "Applied package amount cannot exceed package price");
        }
        if (candidatePackageRefunded.compareTo(candidateApplied) > 0) {
            throw new AppException(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED, "Package refunded amount cannot exceed applied package amount");
        }
        BigDecimal sum = candidateApplied.add(candidateExcess).add(candidateUnallocated);
        if (sum.compareTo(candidateCollected) != 0) {
            throw new AppException(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED,
                    "applied (" + candidateApplied + ") + excess (" + candidateExcess + ") + unallocated ("
                            + candidateUnallocated + ") must equal collected (" + candidateCollected + ")");
        }
    }
}
