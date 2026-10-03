package com.thang.chargeops.refund.repository;

import com.thang.chargeops.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class OwnerSummaryProjectionIntegrationTest {
    @Autowired RefundRepository refunds;
    @Autowired PaymentRepository payments;

    @Test
    void refundSummaryReturnsAllNamedColumnsForOwnerWithNoRefunds() {
        var summary = refunds.summarizeOwnerSimulatorRefunds(UUID.randomUUID());

        assertThat(summary.getTotalCount()).isZero();
        assertThat(summary.getPendingCount()).isZero();
        assertThat(summary.getSucceededCount()).isZero();
        assertThat(summary.getNeedsAdminCount()).isZero();
        assertThat(summary.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getPendingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void financeSummaryReturnsAllNamedColumnsForOwnerWithNoPayments() {
        var summary = payments.summarizeOwnerSimulatorLedger(UUID.randomUUID());

        assertThat(summary.getGrossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getPaymentCount()).isZero();
        assertThat(summary.getPaidCount()).isZero();
    }
}
