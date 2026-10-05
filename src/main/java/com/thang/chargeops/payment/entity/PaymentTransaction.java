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
import static com.thang.chargeops.payment.model.PaymentValues.requiredText;

@Entity
@Table(name = "payment_transactions", uniqueConstraints = @UniqueConstraint(
        name = "ux_payment_transactions_provider_account_ref",
        columnNames = {"provider", "receiving_account_ref", "transaction_ref"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(nullable = false, length = 30, updatable = false)
    private String provider;

    @Column(name = "receiving_account_ref", nullable = false, updatable = false)
    private String receivingAccountRef;

    @Column(name = "transaction_ref", nullable = false, updatable = false)
    private String transactionRef;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "provider_paid_at", updatable = false)
    private Instant providerPaidAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING) @Column(name = "application_classification", nullable = false, length = 30)
    private PaymentApplicationClassification applicationClassification;

    @Column(name = "application_reason", columnDefinition = "text")
    private String applicationReason;

    @Column(name = "payment_code", length = 50, updatable = false)
    private String paymentCode;

    @Column(name = "va_number", updatable = false)
    private String vaNumber;

    @Column(name = "transfer_content", columnDefinition = "text", updatable = false)
    private String transferContent;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_payload", columnDefinition = "jsonb", updatable = false)
    private String rawPayload;

    @Version @Column(nullable = false) private Long version = 0L;

    /** Only verified incoming receipts reach here; provider cannot dictate APPLIED. */
    public static PaymentTransaction create(Payment payment, NormalizedReceipt receipt) {
        if (receipt == null) throw conflict("Receipt is required");
        PaymentTransaction tx = new PaymentTransaction();
        tx.provider = requiredText(receipt.provider(), 30, "Provider");
        tx.receivingAccountRef = requiredText(receipt.receivingAccountRef(), 255, "Merchant account");
        tx.transactionRef = requiredText(receipt.transactionRef(), 255, "Transaction reference");
        if (receipt.amount() == null || receipt.amount().signum() <= 0 || receipt.amount().precision() - receipt.amount().scale() > 17)
            throw conflict("Receipt amount must be positive and fit numeric(19,2)");
        try { tx.amount = receipt.amount().setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw conflict("Receipt amount has excessive decimals"); }
        tx.currency = requiredText(receipt.currency(), 3, "Currency");
        if (!tx.currency.matches("[A-Z]{3}") || receipt.receivedAt() == null) throw conflict("Currency and server receivedAt are required");
        tx.providerPaidAt = receipt.providerPaidAt();
        tx.receivedAt = receipt.receivedAt();
        tx.paymentCode = receipt.paymentCode() == null ? null : requiredText(receipt.paymentCode(), 50, "Payment code");
        tx.vaNumber = receipt.vaNumber() == null ? null : requiredText(receipt.vaNumber(), 255, "VA");
        tx.transferContent = receipt.transferContent();
        tx.rawPayload = receipt.rawPayload();
        tx.payment = payment;
        tx.applicationClassification = PaymentApplicationClassification.UNAPPLIED;
        tx.applicationReason = payment == null ? "UNMATCHED" : "NOT_PROCESSED";
        return tx;
    }

    /** Reconciliation may add a missing link but must never move an existing receipt. */
    public void attachPayment(Payment targetPayment) {
        if (targetPayment == null) throw conflict("Payment is required");
        if (payment != null && payment != targetPayment
                && (payment.getId() == null || !payment.getId().equals(targetPayment.getId())))
            throw conflict("Cannot reassign receipt");
        if (!provider.equals(targetPayment.getProvider()) || !receivingAccountRef.equals(targetPayment.getReceivingAccountRef()))
            throw conflict("Merchant identity mismatch");
        payment = targetPayment;
    }

    public void noteUnapplied(String reason) {
        if (applicationClassification == PaymentApplicationClassification.APPLIED) throw conflict("Cannot declassify accepted receipt");
        applicationReason = requiredText(reason, 1000, "Unapplied reason");
    }

    // Only Payment.acceptReceipt can apply a receipt after exact-order validation.
    void apply() {
        if (payment == null || applicationClassification != PaymentApplicationClassification.UNAPPLIED) throw conflict("Receipt cannot be applied");
        applicationClassification = PaymentApplicationClassification.APPLIED;
        applicationReason = null;
    }

    private static AppException conflict(String message) { return new AppException(PaymentErrorCode.STATE_CONFLICT, message); }
}
