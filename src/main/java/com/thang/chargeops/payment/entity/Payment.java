package com.thang.chargeops.payment.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static com.thang.chargeops.payment.model.PaymentValues.*;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends AuditableEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private PaymentStatus status;
    @Enumerated(EnumType.STRING) @Column(length = 30)
    private PaymentMethod method;
    /** Owner revenue, payout and accounting projections must include LIVE only. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private PaymentEnvironment environment;
    // Historical transaction reference; do not repurpose as provider Order ID.
    @Column(name = "gateway_txn_ref") private String gatewayTxnRef;
    @Column(name = "refund_amount", precision = 15, scale = 2) private BigDecimal refundAmount;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(length = 30) private String provider;
    @Column(name = "receiving_account_ref") private String receivingAccountRef;
    @Column(length = 3) private String currency;
    @Version @Column(nullable = false) private Long version = 0L;
    @Column(name = "needs_reconciliation", nullable = false) private boolean needsReconciliation;
    @Column(name = "payment_code", length = 50, updatable = false) private String paymentCode;
    @Column(name = "provider_order_ref") private String providerOrderRef;
    @Column(name = "va_number") private String vaNumber;
    @Column(name = "provider_expires_at") private Instant providerExpiresAt;
    @Column(name = "qr_code", columnDefinition = "text") private String qrCode;
    @Column(name = "qr_code_url", columnDefinition = "text") private String qrCodeUrl;

    /** Local creation only. Persist the code before calling SePay Create Order. */
    public static Payment createPending(PendingPaymentSpec spec) {
        if (spec == null || spec.booking() == null) throw conflict("Booking and payment spec are required");
        if (spec.method() != PaymentMethod.BANK_TRANSFER && spec.method() != PaymentMethod.SIMULATOR) {
            throw new AppException(PaymentErrorCode.METHOD_INVALID, "Method has no exact Order VA contract");
        }
        BigDecimal expected = exactVnd(spec.amount());
        if (!"VND".equals(spec.currency())) throw conflict("Order VA currency must be VND");
        Payment payment = new Payment();
        payment.provider = requiredText(spec.provider(), 30, "Provider");
        payment.receivingAccountRef = requiredText(spec.receivingAccountRef(), 255, "Merchant account reference");
        payment.booking = spec.booking();
        payment.amount = expected;
        payment.method = spec.method();
        payment.environment = Objects.requireNonNull(
                spec.environment(),
                "Payment environment must not be null"
        );
        payment.currency = "VND";
        payment.status = PaymentStatus.PENDING;
        payment.refundAmount = BigDecimal.ZERO.setScale(2);
        payment.paymentCode = "CO" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return payment;
    }

    /** Bind the VA already returned by Create Order; no separate VA creation call. */
    public void bindOrder(OrderCheckout checkout, Instant now) {
        ensureOrderPayment();
        if (checkout == null || now == null) throw conflict("Checkout and server time are required");
        String ref = requiredText(checkout.orderRef(), 255, "Order reference");
        String va = requiredText(checkout.vaNumber(), 255, "VA number");
        if (!paymentCode.equals(checkout.orderCode()) || amount.compareTo(exactVnd(checkout.amount())) != 0
                || checkout.expiresAt() == null) throw conflict("Order does not match the pending payment");
        if (providerOrderRef != null) {
            if (providerOrderRef.equals(ref) && vaNumber.equals(va) && providerExpiresAt.equals(checkout.expiresAt())) return;
            throw conflict("Cannot replace an existing Order/VA");
        }
        ensurePendingWithinHold(now);
        if (!now.isBefore(checkout.expiresAt())) throw conflict("Provider Order has expired");
        this.providerOrderRef = ref;
        this.vaNumber = va;
        this.providerExpiresAt = checkout.expiresAt();
        this.qrCode = checkout.qrCode();
        this.qrCodeUrl = checkout.qrCodeUrl();
    }

    /**
     * Accept one whole receipt, never a sum or a slice.
     * BKG-026 caller must lock Connector -> Booking -> Payment and persist receipt,
     * Payment and Booking confirmation in ONE database transaction.
     */
    public void acceptReceipt(PaymentTransaction receipt, Instant acceptedAt) {
        ensureOrderPayment();
        ensurePendingWithinHold(acceptedAt);
        if (providerOrderRef == null || providerExpiresAt == null || !acceptedAt.isBefore(providerExpiresAt)) throw conflict("No active Order");
        if (receipt == null || !samePayment(receipt.getPayment())
                || receipt.getApplicationClassification() != PaymentApplicationClassification.UNAPPLIED
                || !provider.equals(receipt.getProvider()) || !receivingAccountRef.equals(receipt.getReceivingAccountRef())
                || !currency.equals(receipt.getCurrency()) || !paymentCode.equals(receipt.getPaymentCode())
                || !vaNumber.equals(receipt.getVaNumber()) || amount.compareTo(receipt.getAmount()) != 0) {
            throw conflict("Receipt does not match this exact Order payment");
        }
        receipt.apply();
        status = PaymentStatus.PAID;
        paidAt = acceptedAt;
    }

    /** Successful full refund only; obligation/attempt idempotency belongs to Refund service. */
    public void recordFullRefund(BigDecimal successfulAmount) {
        ensureOrderPayment();
        if (successfulAmount == null || amount.compareTo(successfulAmount) != 0) throw conflict("Only full package refund is supported");
        if (status == PaymentStatus.REFUNDED && amount.compareTo(refundAmount) == 0) return;
        if (status != PaymentStatus.PAID || paidAt == null) throw conflict("Only accepted payment can be refunded");
        refundAmount = amount;
        status = PaymentStatus.REFUNDED;
    }

    public void markFailed() {
        ensureOrderPayment();
        if (status != PaymentStatus.PENDING && status != PaymentStatus.FAILED) throw conflict("Cannot downgrade paid/refunded payment");
        status = PaymentStatus.FAILED;
    }

    private boolean samePayment(Payment other) {
        return other == this || (other != null && getId() != null && Objects.equals(getId(), other.getId()));
    }

    private void ensureOrderPayment() {
        if (paymentCode == null || needsReconciliation) {
            throw new AppException(PaymentErrorCode.RECONCILIATION_REQUIRED, "Historical payment requires explicit reconciliation");
        }
    }

    private void ensurePendingWithinHold(Instant now) {
        if (now == null || (status != PaymentStatus.PENDING && status != PaymentStatus.FAILED)
                || booking.getStatus() != BookingStatus.PENDING || booking.getExpiresAt() == null
                || !now.isBefore(booking.getExpiresAt())) throw conflict("Booking is not awaiting payment within its hold");
    }

    private static AppException conflict(String message) { return new AppException(PaymentErrorCode.STATE_CONFLICT, message); }
}
