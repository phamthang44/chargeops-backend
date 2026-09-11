package com.thang.chargeops.payment;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class PaymentTransactionEntityTest {

    private final Payment mockPayment = Mockito.mock(Payment.class);

    private NormalizedReceipt validReceipt(PaymentApplicationClassification classification) {
        return new NormalizedReceipt(
                "SEPAY",
                "ACC-123456",
                "TX-999",
                new BigDecimal("120000.00"),
                "VND",
                Instant.parse("2026-09-11T10:00:00Z"),
                Instant.parse("2026-09-11T10:00:05Z"),
                classification,
                "BK1234",
                "Payment for BK1234",
                "{\"gateway\":\"SEPAY\",\"id\":123}"
        );
    }

    @Test
    @DisplayName("create populates all fields and preserves receivedAt from caller")
    void create_populatesFieldsCorrectly() {
        NormalizedReceipt receipt = validReceipt(PaymentApplicationClassification.UNAPPLIED);
        PaymentTransaction tx = PaymentTransaction.create(mockPayment, receipt);

        assertThat(tx.getPayment()).isSameAs(mockPayment);
        assertThat(tx.getProvider()).isEqualTo("SEPAY");
        assertThat(tx.getReceivingAccountRef()).isEqualTo("ACC-123456");
        assertThat(tx.getTransactionRef()).isEqualTo("TX-999");
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(tx.getCurrency()).isEqualTo("VND");
        assertThat(tx.getProviderPaidAt()).isEqualTo(Instant.parse("2026-09-11T10:00:00Z"));
        assertThat(tx.getReceivedAt()).isEqualTo(Instant.parse("2026-09-11T10:00:05Z"));
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);
        assertThat(tx.getPaymentCode()).isEqualTo("BK1234");
        assertThat(tx.getTransferContent()).isEqualTo("Payment for BK1234");
        assertThat(tx.getRawPayload()).isEqualTo("{\"gateway\":\"SEPAY\",\"id\":123}");
    }

    @Test
    @DisplayName("create rejects null receipt or missing identity fields")
    void create_validatesRequiredFields() {
        assertThatThrownBy(() -> PaymentTransaction.create(mockPayment, null))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

        NormalizedReceipt blankProvider = new NormalizedReceipt(
                "  ", "ACC-0", "TX-1", BigDecimal.valueOf(100), "VND",
                null, Instant.now(), PaymentApplicationClassification.UNMATCHED, null, null, null
        );
        assertThatThrownBy(() -> PaymentTransaction.create(null, blankProvider))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

        NormalizedReceipt nonPositiveAmount = new NormalizedReceipt(
                "SEPAY", "ACC-0", "TX-1", BigDecimal.ZERO, "VND",
                null, Instant.now(), PaymentApplicationClassification.UNMATCHED, null, null, null
        );
        assertThatThrownBy(() -> PaymentTransaction.create(null, nonPositiveAmount))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

        NormalizedReceipt badCurrency = new NormalizedReceipt(
                "SEPAY", "ACC-0", "TX-1", BigDecimal.valueOf(100), "vnd",
                null, Instant.now(), PaymentApplicationClassification.UNMATCHED, null, null, null
        );
        assertThatThrownBy(() -> PaymentTransaction.create(null, badCurrency))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));
    }

    @Test
    @DisplayName("create APPLIED requires associated Payment; UNMATCHED allows null Payment")
    void create_appliedRequiresPayment() {
        NormalizedReceipt appliedReceipt = validReceipt(PaymentApplicationClassification.APPLIED);
        assertThatThrownBy(() -> PaymentTransaction.create(null, appliedReceipt))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.STATE_CONFLICT));

        NormalizedReceipt unmatchedReceipt = validReceipt(PaymentApplicationClassification.UNMATCHED);
        PaymentTransaction tx = PaymentTransaction.create(null, unmatchedReceipt);
        assertThat(tx.getPayment()).isNull();
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNMATCHED);
    }

    @Test
    @DisplayName("attachPayment and reclassify reject reassigning to another Payment or declassifying APPLIED")
    void attachAndReclassify_guards() {
        UUID paymentId1 = UUID.randomUUID();
        UUID paymentId2 = UUID.randomUUID();
        Payment payment1 = Mockito.mock(Payment.class);
        when(payment1.getId()).thenReturn(paymentId1);
        Payment payment2 = Mockito.mock(Payment.class);
        when(payment2.getId()).thenReturn(paymentId2);

        PaymentTransaction tx = PaymentTransaction.create(null, validReceipt(PaymentApplicationClassification.UNMATCHED));

        // Attach payment 1
        tx.attachPayment(payment1, PaymentApplicationClassification.APPLIED);
        assertThat(tx.getPayment()).isSameAs(payment1);
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.APPLIED);

        // Cannot reassign to payment 2
        assertThatThrownBy(() -> tx.attachPayment(payment2, PaymentApplicationClassification.APPLIED))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.STATE_CONFLICT));

        // Cannot declassify APPLIED
        assertThatThrownBy(() -> tx.reclassify(PaymentApplicationClassification.UNAPPLIED))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.STATE_CONFLICT));
    }
}
