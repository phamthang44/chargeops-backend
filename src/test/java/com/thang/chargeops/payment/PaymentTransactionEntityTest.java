package com.thang.chargeops.payment;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static com.thang.chargeops.payment.PaymentEntityTest.*;

class PaymentTransactionEntityTest {
    @Test void receiptStartsUnappliedWithoutProviderClassification() {
        var p = pending();
        var tx = receipt(p, "TX1", "120000", p.getPaymentCode(), "VA001");
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);
        assertThat(tx.getApplicationReason()).isEqualTo("NOT_PROCESSED");
        assertThat(tx.getReceivedAt()).isEqualTo(NOW);
        assertThat(tx.getProviderPaidAt()).isEqualTo(NOW);
        assertThat(tx.getRawPayload()).isEqualTo("{}");
        assertThat(tx.getVaNumber()).isEqualTo("VA001");
    }
    @Test void unmatchedReceiptPreservesMoneyAndReason() {
        var tx = PaymentTransaction.create(null, new NormalizedReceipt("SEPAY", "merchant-test", "TX1", new BigDecimal("200000"), "VND", NOW, NOW, null, null, "unknown", "{}"));
        assertThat(tx.getPayment()).isNull();
        assertThat(tx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);
        assertThat(tx.getApplicationReason()).isEqualTo("UNMATCHED");
        var p = pending();
        tx.attachPayment(p);
        assertThat(tx.getPayment()).isSameAs(p);
        assertThatThrownBy(() -> tx.attachPayment(pending())).isInstanceOf(AppException.class);
        tx.noteUnapplied("AMOUNT_MISMATCH");
        assertThat(tx.getAmount()).isEqualByComparingTo("200000");
    }
    @Test void rejectsInvalidOrMissingReceiptEvidence() {
        assertThatThrownBy(() -> PaymentTransaction.create(null, null)).isInstanceOf(AppException.class);
        for (var data : new NormalizedReceipt[] {
            new NormalizedReceipt("", "a", "t", BigDecimal.ONE, "VND", NOW, NOW, null, null, null, null),
            new NormalizedReceipt("SEPAY", " ", "t", BigDecimal.ONE, "VND", NOW, NOW, null, null, null, null),
            new NormalizedReceipt("SEPAY", "a", "", BigDecimal.ONE, "VND", NOW, NOW, null, null, null, null),
            new NormalizedReceipt("SEPAY", "a", "t", BigDecimal.ZERO, "VND", NOW, NOW, null, null, null, null),
            new NormalizedReceipt("SEPAY", "a", "t", new BigDecimal("1.001"), "VND", NOW, NOW, null, null, null, null),
            new NormalizedReceipt("SEPAY", "a", "t", BigDecimal.ONE, "vnd", NOW, NOW, null, null, null, null),
            new NormalizedReceipt("SEPAY", "a", "t", BigDecimal.ONE, "VND", NOW, null, null, null, null, null),
            new NormalizedReceipt("SEPAY", "a", "t", BigDecimal.ONE, "VND", NOW, NOW, null, "X".repeat(51), null, null)
        }) assertThatThrownBy(() -> PaymentTransaction.create(null, data)).isInstanceOf(AppException.class);
    }
    @Test void blankReasonAndWrongMerchantCannotBeAttached() {
        var tx = PaymentTransaction.create(null, new NormalizedReceipt("SEPAY", "other", "t", BigDecimal.ONE, "VND", NOW, NOW, null, null, null, null));
        assertThatThrownBy(() -> tx.attachPayment(pending())).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> tx.noteUnapplied(" ")).isInstanceOf(AppException.class);
    }
}
