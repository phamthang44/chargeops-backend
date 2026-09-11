package com.thang.chargeops.payment.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.regex.Pattern;

@Entity
@Table(name = "payment_transactions", indexes = {
        @Index(name = "idx_payment_transactions_payment", columnList = "payment_id"),
        @Index(name = "idx_payment_transactions_classification_received", columnList = "application_classification, received_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "ux_payment_transactions_provider_account_ref",
                columnNames = {"provider", "receiving_account_ref", "transaction_ref"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends AuditableEntity {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "provider", nullable = false, length = 30, updatable = false)
    private String provider;

    @Column(name = "receiving_account_ref", nullable = false, length = 255, updatable = false)
    private String receivingAccountRef;

    @Column(name = "transaction_ref", nullable = false, length = 255, updatable = false)
    private String transactionRef;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "provider_paid_at", updatable = false)
    private Instant providerPaidAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_classification", nullable = false, length = 30)
    private PaymentApplicationClassification applicationClassification;

    @Column(name = "payment_code", length = 50, updatable = false)
    private String paymentCode;

    @Column(name = "transfer_content", columnDefinition = "text", updatable = false)
    private String transferContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb", updatable = false)
    private String rawPayload;

    /**
     * Factory for creating a PaymentTransaction from normalized receipt input.
     */
    public static PaymentTransaction create(Payment payment, NormalizedReceipt receipt) {
        if (receipt == null) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt data is required");
        }
        validateReceiptFields(receipt);

        if (receipt.classification() == PaymentApplicationClassification.APPLIED && payment == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot create APPLIED receipt without an associated Payment");
        }

        PaymentTransaction tx = new PaymentTransaction();
        tx.payment = payment;
        tx.provider = receipt.provider().trim();
        tx.receivingAccountRef = receipt.receivingAccountRef().trim();
        tx.transactionRef = receipt.transactionRef().trim();
        tx.amount = receipt.amount().setScale(2, RoundingMode.UNNECESSARY);
        tx.currency = receipt.currency().trim();
        tx.providerPaidAt = receipt.providerPaidAt();
        tx.receivedAt = receipt.receivedAt() != null ? receipt.receivedAt() : Instant.now();
        tx.applicationClassification = receipt.classification();
        tx.paymentCode = receipt.paymentCode() != null ? receipt.paymentCode().trim() : null;
        tx.transferContent = receipt.transferContent();
        tx.rawPayload = receipt.rawPayload();
        return tx;
    }

    private static void validateReceiptFields(NormalizedReceipt receipt) {
        if (receipt.provider() == null || receipt.provider().isBlank()) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt provider must not be blank");
        }
        if (receipt.provider().length() > 30) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt provider length exceeds 30 characters");
        }
        if (receipt.receivingAccountRef() == null || receipt.receivingAccountRef().isBlank()) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receiving account reference must not be blank");
        }
        if (receipt.receivingAccountRef().length() > 255) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receiving account reference exceeds 255 characters");
        }
        if (receipt.transactionRef() == null || receipt.transactionRef().isBlank()) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Transaction reference must not be blank");
        }
        if (receipt.transactionRef().length() > 255) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Transaction reference exceeds 255 characters");
        }
        if (receipt.amount() == null || receipt.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt amount must be strictly positive");
        }
        try {
            receipt.amount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Receipt amount cannot have more than 2 decimal places");
        }
        if (receipt.currency() == null || !CURRENCY_PATTERN.matcher(receipt.currency().trim()).matches()) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Currency must be 3 uppercase alphabetic characters");
        }
        if (receipt.classification() == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Application classification is required");
        }
    }

    /**
     * Attaches an unmatched or unapplied transaction to a Payment.
     */
    public void attachPayment(Payment targetPayment, PaymentApplicationClassification newClassification) {
        if (targetPayment == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Target payment must not be null");
        }
        if (this.payment != null && !this.payment.getId().equals(targetPayment.getId())) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot reassign receipt already attached to another Payment");
        }
        if (newClassification == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Classification is required when attaching Payment");
        }
        if (this.applicationClassification == PaymentApplicationClassification.APPLIED
                && newClassification != PaymentApplicationClassification.APPLIED) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot declassify an APPLIED receipt");
        }
        this.payment = targetPayment;
        this.applicationClassification = newClassification;
    }

    /**
     * Reclassifies receipt under domain guard.
     */
    public void reclassify(PaymentApplicationClassification newClassification) {
        if (newClassification == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Classification cannot be null");
        }
        if (newClassification == PaymentApplicationClassification.APPLIED && this.payment == null) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot mark receipt as APPLIED without attached Payment");
        }
        if (this.applicationClassification == PaymentApplicationClassification.APPLIED
                && newClassification != PaymentApplicationClassification.APPLIED) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT, "Cannot declassify an APPLIED receipt");
        }
        this.applicationClassification = newClassification;
    }
}
