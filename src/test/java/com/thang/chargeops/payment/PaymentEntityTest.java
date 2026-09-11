package com.thang.chargeops.payment;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEntityTest {

    private final Booking mockBooking = Mockito.mock(Booking.class);

    private PendingPaymentSpec validSpec(BigDecimal amount) {
        return new PendingPaymentSpec(
                mockBooking,
                amount,
                PaymentMethod.BANK_TRANSFER,
                "SEPAY",
                "ACC-123456",
                "VND"
        );
    }

    @Nested
    @DisplayName("1. Factory and Spec Validation")
    class FactoryTests {

        @Test
        @DisplayName("createPending initializes PENDING status, zero projections, and valid metadata")
        void createPending_initializesCorrectly() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));

            assertThat(payment.getBooking()).isSameAs(mockBooking);
            assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
            assertThat(payment.getProvider()).isEqualTo("SEPAY");
            assertThat(payment.getReceivingAccountRef()).isEqualTo("ACC-123456");
            assertThat(payment.getCurrency()).isEqualTo("VND");
            assertThat(payment.getVersion()).isEqualTo(0L);
            assertThat(payment.isNeedsReconciliation()).isFalse();
            assertThat(payment.getPaidAt()).isNull();
            assertThat(payment.getGatewayTxnRef()).isNull();

            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getPackageRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getExcessAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("createPending rejects null spec or null booking")
        void createPending_rejectsNullSpecOrBooking() {
            assertThatThrownBy(() -> Payment.createPending(null))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            PendingPaymentSpec specNoBooking = new PendingPaymentSpec(
                    null, BigDecimal.valueOf(100000), PaymentMethod.SIMULATOR, "SIMULATOR", "ACC-0", "VND"
            );
            assertThatThrownBy(() -> Payment.createPending(specNoBooking))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.STATE_CONFLICT));
        }

        @Test
        @DisplayName("createPending rejects negative or invalid decimal amounts")
        void createPending_rejectsNegativeOrExcessiveDecimals() {
            PendingPaymentSpec negativeSpec = new PendingPaymentSpec(
                    mockBooking, BigDecimal.valueOf(-1), PaymentMethod.SIMULATOR, "SIMULATOR", "ACC-0", "VND"
            );
            assertThatThrownBy(() -> Payment.createPending(negativeSpec))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            PendingPaymentSpec threeDecimalsSpec = new PendingPaymentSpec(
                    mockBooking, new BigDecimal("120000.123"), PaymentMethod.SIMULATOR, "SIMULATOR", "ACC-0", "VND"
            );
            assertThatThrownBy(() -> Payment.createPending(threeDecimalsSpec))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));
        }

        @Test
        @DisplayName("createPending rejects invalid currency or blank identity metadata")
        void createPending_rejectsInvalidCurrencyOrIdentity() {
            PendingPaymentSpec badCurrency = new PendingPaymentSpec(
                    mockBooking, BigDecimal.valueOf(100000), PaymentMethod.SIMULATOR, "SIMULATOR", "ACC-0", "vnd"
            );
            assertThatThrownBy(() -> Payment.createPending(badCurrency))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            PendingPaymentSpec blankProvider = new PendingPaymentSpec(
                    mockBooking, BigDecimal.valueOf(100000), PaymentMethod.SIMULATOR, "   ", "ACC-0", "VND"
            );
            assertThatThrownBy(() -> Payment.createPending(blankProvider))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            PendingPaymentSpec nullMethod = new PendingPaymentSpec(
                    mockBooking, BigDecimal.valueOf(100000), null, "SIMULATOR", "ACC-0", "VND"
            );
            assertThatThrownBy(() -> Payment.createPending(nullMethod))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.METHOD_INVALID));
        }
    }

    @Nested
    @DisplayName("2. Projections and Accounting Invariants")
    class AccountingTests {

        @Test
        @DisplayName("Decimal equality: compareTo handles trailing zeros properly")
        void decimalEquality_comparesProperly() {
            Payment payment = Payment.createPending(validSpec(new BigDecimal("120000")));
            assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        }

        @Test
        @DisplayName("recordUnallocatedReceipt increases collectedAmount and unallocatedAmount only, stays PENDING")
        void recordUnallocatedReceipt_updatesUnallocatedOnly() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));

            payment.recordUnallocatedReceipt(new BigDecimal("120000.00"));

            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getPaidAt()).isNull();
        }

        @Test
        @DisplayName("applyPackagePayment shifts funds from unallocated to applied, transitions to PAID, sets paidAt")
        void applyPackagePayment_transitionsToPaid() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));
            payment.recordUnallocatedReceipt(new BigDecimal("120000.00"));

            Instant acceptedAt = Instant.parse("2026-09-11T10:00:00Z");
            payment.applyPackagePayment(new BigDecimal("120000.00"), acceptedAt);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getPaidAt()).isEqualTo(acceptedAt);
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        }

        @Test
        @DisplayName("applyPackagePayment rejects amount not matching package price or insufficient unallocated")
        void applyPackagePayment_guards() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));
            payment.recordUnallocatedReceipt(new BigDecimal("60000.00"));

            Instant now = Instant.now();

            // Mismatched amount
            assertThatThrownBy(() -> payment.applyPackagePayment(new BigDecimal("60000.00"), now))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            // Insufficient unallocated funds
            assertThatThrownBy(() -> payment.applyPackagePayment(new BigDecimal("120000.00"), now))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            // Failure atomicity: unallocated funds untouched
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(new BigDecimal("60000.00"));
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("Single overpayment (120k package, 200k receipt): holds 200k unallocated without auto-confirming")
        void singleOverpayment_holdsUnallocatedWithoutAutoConfirming() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));
            payment.recordUnallocatedReceipt(new BigDecimal("200000.00"));

            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(new BigDecimal("200000.00"));
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(new BigDecimal("200000.00"));
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getExcessAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("Two receipts scenario (120k package, TX1 120k + TX2 120k): excess classified, stays PAID")
        void twoReceipts_excessScenario() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));

            // TX1 arrives: 120k
            payment.recordUnallocatedReceipt(new BigDecimal("120000.00"));
            payment.applyPackagePayment(new BigDecimal("120000.00"), Instant.parse("2026-09-11T10:00:00Z"));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));

            // TX2 arrives: another 120k
            payment.recordUnallocatedReceipt(new BigDecimal("120000.00"));
            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(new BigDecimal("240000.00"));
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));

            // Classify as excess
            payment.classifyExcess(new BigDecimal("120000.00"));

            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(new BigDecimal("240000.00"));
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getExcessAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getUnallocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

            // Core Acceptance Criterion: package status remains PAID, packageRefundedAmount remains 0
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getPackageRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Cumulative package refund transitions to PARTIALLY_REFUNDED and REFUNDED properly")
        void cumulativePackageRefund_transitions() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));
            payment.recordUnallocatedReceipt(new BigDecimal("120000.00"));
            payment.applyPackagePayment(new BigDecimal("120000.00"), Instant.parse("2026-09-11T10:00:00Z"));

            // Partial refund 60k
            payment.recordCumulativePackageRefund(new BigDecimal("60000.00"));
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(payment.getPackageRefundedAmount()).isEqualByComparingTo(new BigDecimal("60000.00"));
            // Gross collected and applied do NOT decrease
            assertThat(payment.getCollectedAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
            assertThat(payment.getAppliedToPackageAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));

            // Replay same total is idempotent no-op
            payment.recordCumulativePackageRefund(new BigDecimal("60000.00"));
            assertThat(payment.getPackageRefundedAmount()).isEqualByComparingTo(new BigDecimal("60000.00"));

            // Decreasing total is rejected
            assertThatThrownBy(() -> payment.recordCumulativePackageRefund(new BigDecimal("50000.00")))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            // Exceeding applied is rejected
            assertThatThrownBy(() -> payment.recordCumulativePackageRefund(new BigDecimal("130000.00")))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

            // Full refund 120k
            payment.recordCumulativePackageRefund(new BigDecimal("120000.00"));
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.getPackageRefundedAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        }

        @Test
        @DisplayName("markFailed cannot downgrade PAID or REFUNDED payment")
        void markFailed_cannotDowngradePaidOrRefunded() {
            Payment payment = Payment.createPending(validSpec(BigDecimal.valueOf(120000)));
            payment.recordUnallocatedReceipt(new BigDecimal("120000.00"));
            payment.applyPackagePayment(new BigDecimal("120000.00"), Instant.parse("2026-09-11T10:00:00Z"));

            assertThatThrownBy(payment::markFailed)
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(PaymentErrorCode.STATE_CONFLICT));
        }
    }
}
