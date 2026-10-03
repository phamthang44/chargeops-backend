package com.thang.chargeops.payment.service;

import com.thang.chargeops.payment.projection.OwnerFinanceTotalsProjection;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.repository.RefundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnerFinanceServiceImplTest {
    @Mock CurrentProfileProvider currentProfileProvider;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock RefundRepository refundRepository;
    @InjectMocks OwnerFinanceServiceImpl service;

    @Test
    void summaryReadsNamedAggregateColumns() {
        UUID ownerId = UUID.randomUUID();
        OwnerFinanceTotalsProjection totals = mock(OwnerFinanceTotalsProjection.class);
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(paymentRepository.summarizeOwnerSimulatorLedger(ownerId)).thenReturn(totals);
        when(totals.getGrossAmount()).thenReturn(BigDecimal.valueOf(10000));
        when(totals.getRefundedAmount()).thenReturn(BigDecimal.valueOf(3000));
        when(totals.getPaymentCount()).thenReturn(4L);
        when(totals.getPaidCount()).thenReturn(2L);
        when(refundRepository.sumOwnerPendingSimulatorRefunds(ownerId)).thenReturn(BigDecimal.valueOf(1000));

        var summary = service.summary();

        assertEquals(10000, summary.grossVnd());
        assertEquals(3000, summary.refundedVnd());
        assertEquals(7000, summary.netVnd());
        assertEquals(1000, summary.pendingRefundVnd());
        assertEquals(4, summary.totalBookings());
        assertEquals(2, summary.paidBookings());
    }
}
