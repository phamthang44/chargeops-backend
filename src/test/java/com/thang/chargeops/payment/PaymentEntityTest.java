package com.thang.chargeops.payment;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.payment.entity.*;
import com.thang.chargeops.payment.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentEntityTest {
    static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");
    static final Instant EXPIRY = NOW.plusSeconds(600);

    static Payment pending() {
        Booking booking = mock(Booking.class);
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(EXPIRY);
        return Payment.createPending(new PendingPaymentSpec(booking, new BigDecimal("120000"), PaymentMethod.BANK_TRANSFER, "SEPAY", "merchant-test", "VND"));
    }
    static OrderCheckout checkout(Payment p) {
        return new OrderCheckout("order-001", p.getPaymentCode(), "VA001", p.getAmount(), EXPIRY, "qr-data", "https://example.test/qr");
    }
    static Payment bound() {
        Payment p = pending(); p.bindOrder(checkout(p), NOW); return p;
    }
    static PaymentTransaction receipt(Payment p, String ref, String amount, String code, String va) {
        return PaymentTransaction.create(p, new NormalizedReceipt("SEPAY", "merchant-test", ref, new BigDecimal(amount), "VND", NOW, NOW, va, code, "arbitrary content", "{}"));
    }

    @Test void createsLocalCodeBeforeProviderCall() {
        Payment p = pending();
        assertThat(p.getPaymentCode()).matches("[A-Z0-9]{6,50}");
        assertThat(p.getPaymentCode()).isNotEqualTo(pending().getPaymentCode());
        assertThat(p.getProviderOrderRef()).isNull();
        assertThat(p.getVaNumber()).isNull();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getEnvironment()).isEqualTo(PaymentEnvironment.LIVE);
        assertThat(p.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(p.isNeedsReconciliation()).isFalse();
    }

    @Test void preservesExplicitTestEnvironmentForSandboxBankTransfer() {
        Booking booking = mock(Booking.class);
        Payment payment = Payment.createPending(new PendingPaymentSpec(
                booking,
                new BigDecimal("120000"),
                PaymentMethod.BANK_TRANSFER,
                "SEPAY",
                "sandbox-account",
                "VND",
                PaymentEnvironment.TEST
        ));

        assertThat(payment.getEnvironment()).isEqualTo(PaymentEnvironment.TEST);
    }

    @Test void defaultsSimulatorPaymentsToSimulatorEnvironment() {
        Booking booking = mock(Booking.class);
        Payment payment = Payment.createPending(new PendingPaymentSpec(
                booking,
                new BigDecimal("120000"),
                PaymentMethod.SIMULATOR,
                "SIMULATOR",
                "SIMULATOR",
                "VND"
        ));

        assertThat(payment.getEnvironment()).isEqualTo(PaymentEnvironment.SIMULATOR);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.01", "1000000000000"})
    void rejectsAmountsOutsideExactVnd(String amount) {
        assertThatThrownBy(() -> Payment.createPending(new PendingPaymentSpec(mock(Booking.class), new BigDecimal(amount), PaymentMethod.BANK_TRANSFER, "SEPAY", "merchant-test", "VND")))
            .isInstanceOf(AppException.class);
    }

    @Test void rejectsInvalidFactoryMetadata() {
        assertThatThrownBy(() -> Payment.createPending(null)).isInstanceOf(AppException.class);
        for (var spec : new PendingPaymentSpec[] {
                new PendingPaymentSpec(null, BigDecimal.ONE, PaymentMethod.BANK_TRANSFER, "SEPAY", "acc", "VND"),
                new PendingPaymentSpec(mock(Booking.class), BigDecimal.ONE, PaymentMethod.VNPAY, "SEPAY", "acc", "VND"),
                new PendingPaymentSpec(mock(Booking.class), BigDecimal.ONE, PaymentMethod.BANK_TRANSFER, "", "acc", "VND"),
                new PendingPaymentSpec(mock(Booking.class), BigDecimal.ONE, PaymentMethod.BANK_TRANSFER, "SEPAY", " ", "VND"),
                new PendingPaymentSpec(mock(Booking.class), BigDecimal.ONE, PaymentMethod.BANK_TRANSFER, "SEPAY", "acc", "USD")
        }) assertThatThrownBy(() -> Payment.createPending(spec)).isInstanceOf(AppException.class);
    }

    @Test void bindsOnceAndReplayDoesNotChangeExpiry() {
        Payment p = pending(); OrderCheckout c = checkout(p);
        p.bindOrder(c, NOW); p.bindOrder(c, NOW.plusSeconds(20));
        assertThat(p.getProviderOrderRef()).isEqualTo("order-001");
        assertThat(p.getProviderExpiresAt()).isEqualTo(EXPIRY);
        assertThatThrownBy(() -> p.bindOrder(new OrderCheckout("other", p.getPaymentCode(), "VA002", p.getAmount(), EXPIRY, null, null), NOW))
            .isInstanceOf(AppException.class);
        assertThat(p.getVaNumber()).isEqualTo("VA001");
    }

    @Test void wrongCheckoutDoesNotMutatePayment() {
        Payment p = pending();
        assertThatThrownBy(() -> p.bindOrder(new OrderCheckout("order", p.getPaymentCode(), "VA001", BigDecimal.ONE, EXPIRY, null, null), NOW))
            .isInstanceOf(AppException.class);
        assertThat(p.getProviderOrderRef()).isNull();
        assertThatThrownBy(() -> p.bindOrder(checkout(p), EXPIRY)).isInstanceOf(AppException.class);
    }

    @Test void exactReceiptAppliesOnceAndContentDoesNotControlMatching() {
        Payment p = bound();
        PaymentTransaction tx = receipt(p, "TX1", "120000.00", p.getPaymentCode(), "VA001");
        p.acceptReceipt(tx, NOW.plusSeconds(1));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.APPLIED);
        assertThat(tx.getApplicationReason()).isNull();
        assertThatThrownBy(() -> p.acceptReceipt(tx, NOW.plusSeconds(2))).isInstanceOf(AppException.class);
        assertThat(p.getPaidAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> tx.noteUnapplied("LATE")).isInstanceOf(AppException.class);
        assertThatThrownBy(p::markFailed).isInstanceOf(AppException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"100000", "200000"})
    void wrongAmountStaysUnappliedWithoutSplitting(String amount) {
        Payment p = bound(); PaymentTransaction tx = receipt(p, "TX1", amount, p.getPaymentCode(), "VA001");
        assertThatThrownBy(() -> p.acceptReceipt(tx, NOW)).isInstanceOf(AppException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getPaidAt()).isNull();
        assertThat(tx.getAmount()).isEqualByComparingTo(amount);
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);
    }

    @Test void wrongOrderVaAccountCurrencyOrPaymentNeverApplies() {
        Payment p = bound();
        for (PaymentTransaction tx : new PaymentTransaction[] {
            receipt(p, "TX1", "120000", "WRONGCODE", "VA001"),
            receipt(p, "TX2", "120000", p.getPaymentCode(), "WRONGVA"),
            receipt(pending(), "TX3", "120000", p.getPaymentCode(), "VA001"),
            PaymentTransaction.create(p, new NormalizedReceipt("OTHER", "merchant-test", "TX4", p.getAmount(), "VND", NOW, NOW, "VA001", p.getPaymentCode(), null, null)),
            PaymentTransaction.create(p, new NormalizedReceipt("SEPAY", "other-account", "TX5", p.getAmount(), "VND", NOW, NOW, "VA001", p.getPaymentCode(), null, null)),
            PaymentTransaction.create(p, new NormalizedReceipt("SEPAY", "merchant-test", "TX6", p.getAmount(), "USD", NOW, NOW, "VA001", p.getPaymentCode(), null, null))
        }) assertThatThrownBy(() -> p.acceptReceipt(tx, NOW)).isInstanceOf(AppException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test void lateReceiptOrCancelledBookingCannotBeAccepted() {
        Payment p = bound(); var tx = receipt(p, "TX1", "120000", p.getPaymentCode(), "VA001");
        assertThatThrownBy(() -> p.acceptReceipt(tx, EXPIRY)).isInstanceOf(AppException.class);
        tx.noteUnapplied("LATE");
        assertThat(tx.getApplicationReason()).isEqualTo("LATE");
        when(p.getBooking().getStatus()).thenReturn(BookingStatus.CANCELLED);
        assertThatThrownBy(() -> p.acceptReceipt(tx, NOW)).isInstanceOf(AppException.class);
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);
    }

    @Test void providerExpiryAndUnboundOrderAlsoBlockAcceptance() {
        Payment p = pending(); var tx = receipt(p, "TX1", "120000", p.getPaymentCode(), "VA001");
        assertThatThrownBy(() -> p.acceptReceipt(tx, NOW)).isInstanceOf(AppException.class);
        p.bindOrder(new OrderCheckout("order", p.getPaymentCode(), "VA001", p.getAmount(), NOW.plusSeconds(10), null, null), NOW);
        assertThatThrownBy(() -> p.acceptReceipt(tx, NOW.plusSeconds(10))).isInstanceOf(AppException.class);
    }

    @Test void retryAfterFailureWithinOriginalHoldAndFullRefundOnly() {
        Payment p = bound(); p.markFailed();
        p.acceptReceipt(receipt(p, "TX1", "120000", p.getPaymentCode(), "VA001"), NOW);
        assertThatThrownBy(() -> p.recordFullRefund(new BigDecimal("60000"))).isInstanceOf(AppException.class);
        assertThat(p.getRefundAmount()).isEqualByComparingTo("0");
        p.recordFullRefund(p.getAmount()); p.recordFullRefund(p.getAmount());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getRefundAmount()).isEqualByComparingTo(p.getAmount());
    }
}
