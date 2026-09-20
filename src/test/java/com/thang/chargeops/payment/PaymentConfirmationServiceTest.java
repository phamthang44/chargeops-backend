package com.thang.chargeops.payment;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.PaymentConfirmationServiceImpl;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant HOLD_EXPIRY = NOW.plus(Duration.ofMinutes(10));
    private static final Instant START_AT = NOW.plus(Duration.ofHours(2));

    private ConnectorRepository connectorRepository;
    private BookingRepository bookingRepository;
    private PaymentRepository paymentRepository;
    private PaymentTransactionRepository paymentTransactionRepository;
    private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private BookingPolicyConfig bookingPolicyConfig;
    private Clock applicationClock;

    private PaymentConfirmationServiceImpl service;

    private UUID connectorId;
    private UUID bookingId;
    private Booking booking;
    private Payment payment;
    private String paymentCode;
    private String vaNumber;

    @BeforeEach
    void setUp() {
        connectorRepository = mock(ConnectorRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        bookingStatusHistoryRecorder = mock(BookingStatusHistoryRecorder.class);
        bookingPolicyConfig = mock(BookingPolicyConfig.class);
        applicationClock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(bookingPolicyConfig.getCancellationGraceMinutes()).thenReturn(10);

        doAnswer(invocation -> {
            Consumer<Booking> transition = invocation.getArgument(3);
            Booking targetBooking = invocation.getArgument(0);
            if (transition != null) {
                transition.accept(targetBooking);
            }
            return null;
        }).when(bookingStatusHistoryRecorder).recordSystemTransition(any(), any(), any(), any());

        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new PaymentConfirmationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                bookingStatusHistoryRecorder,
                bookingPolicyConfig,
                applicationClock
        );

        connectorId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        Connector connector = mock(Connector.class);
        when(connector.getId()).thenReturn(connectorId);

        booking = mock(Booking.class);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getConnector()).thenReturn(connector);
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(HOLD_EXPIRY);
        when(booking.getStartAt()).thenReturn(START_AT);

        payment = Payment.createPending(new PendingPaymentSpec(
                booking,
                new BigDecimal("120000"),
                PaymentMethod.SIMULATOR,
                "SIMULATOR",
                "SIMULATOR_ACCOUNT",
                "VND"
        ));
        paymentCode = payment.getPaymentCode();
        vaNumber = "VA-SIM-" + paymentCode;

        payment.bindOrder(new OrderCheckout(
                "SIM-ORDER-" + paymentCode,
                paymentCode,
                vaNumber,
                new BigDecimal("120000"),
                HOLD_EXPIRY,
                "SIM_QR_" + paymentCode,
                "https://simulator.chargeops.local/checkout/" + paymentCode
        ), NOW);

        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByPaymentCode(paymentCode)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByVaNumber(vaNumber)).thenReturn(Optional.of(payment));
    }

    private NormalizedReceipt createSimulatorReceipt(String txRef, String amountStr) {
        return new NormalizedReceipt(
                "SIMULATOR",
                "SIMULATOR_ACCOUNT",
                txRef,
                new BigDecimal(amountStr),
                "VND",
                NOW,
                NOW,
                vaNumber,
                paymentCode,
                "Payment " + paymentCode,
                "{\"provider\": \"SIMULATOR\"}"
        );
    }

    @Test
    @DisplayName("1. Valid receipt trong hold: PAID + CONFIRMED + tính đúng freeCancellationDeadline")
    void validReceiptWithinHold_confirmsBookingAndAcceptsReceipt() {
        NormalizedReceipt receipt = createSimulatorReceipt("TX-001", "120000");

        PaymentReceiptResult result = service.processReceipt(receipt);

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);

        // BR-PAY-02: freeCancellationDeadline = min(NOW + 10m, START_AT)
        Instant expectedDeadline = NOW.plus(Duration.ofMinutes(10));
        verify(booking).confirmPayment(NOW, expectedDeadline);
        verify(bookingStatusHistoryRecorder).recordSystemTransition(
                eq(booking),
                eq(BookingStatusReason.PAYMENT_CONFIRMED),
                eq(NOW),
                any()
        );
    }

    @Test
    @DisplayName("2. TX1 lặp ba lần: chỉ xác nhận 1 lần, grace deadline không bị reset")
    void duplicateWebhook_idempotentNoStateMutation() {
        NormalizedReceipt receipt = createSimulatorReceipt("TX-001", "120000");

        // Lần 1: Thành công
        PaymentReceiptResult result1 = service.processReceipt(receipt);
        assertThat(result1.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);

        // Giả lập DB đã có TX-001
        PaymentTransaction savedTx = mock(PaymentTransaction.class);
        when(savedTx.getApplicationClassification()).thenReturn(com.thang.chargeops.common.enums.PaymentApplicationClassification.APPLIED);
        when(paymentTransactionRepository.findByProviderAndReceivingAccountRefAndTransactionRef("SIMULATOR", "SIMULATOR_ACCOUNT", "TX-001"))
                .thenReturn(Optional.of(savedTx));

        // Lần 2 và Lần 3
        PaymentReceiptResult result2 = service.processReceipt(receipt);
        PaymentReceiptResult result3 = service.processReceipt(receipt);

        assertThat(result2.status()).isEqualTo(PaymentReceiptResult.Status.DUPLICATE);
        assertThat(result3.status()).isEqualTo(PaymentReceiptResult.Status.DUPLICATE);

        // Đảm bảo confirmPayment chỉ được gọi đúng 1 lần từ lần đầu tiên
        verify(booking, times(1)).confirmPayment(any(), any());
    }

    @Test
    @DisplayName("3. TX1 và TX2 thật khác nhau: TX1 APPLIED, TX2 lưu UNAPPLIED (ALREADY_PAID), giữ cả hai")
    void multipleRealTransactions_bothSavedOneApplied() {
        NormalizedReceipt receipt1 = createSimulatorReceipt("TX-001", "120000");
        NormalizedReceipt receipt2 = createSimulatorReceipt("TX-002", "120000");

        PaymentReceiptResult result1 = service.processReceipt(receipt1);
        assertThat(result1.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

        // TX2 đến sau khi payment đã PAID
        PaymentReceiptResult result2 = service.processReceipt(receipt2);
        assertThat(result2.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result2.reason()).isEqualTo("ALREADY_PAID");

        // Cả 2 transactions đều được lưu vào repository
        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).getTransactionRef()).isEqualTo("TX-001");
        assertThat(captor.getAllValues().get(1).getTransactionRef()).isEqualTo("TX-002");
        assertThat(captor.getAllValues().get(1).getApplicationReason()).isEqualTo("ALREADY_PAID");
    }

    @Test
    @DisplayName("4. Tiền vào muộn sau hold (LATE): lưu UNAPPLIED, không hồi sinh booking")
    void lateReceipt_markedLateAndBookingNotRevived() {
        Instant lateTime = HOLD_EXPIRY.plusSeconds(10);
        Clock lateClock = Clock.fixed(lateTime, ZoneOffset.UTC);

        PaymentConfirmationServiceImpl lateService = new PaymentConfirmationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                bookingStatusHistoryRecorder,
                bookingPolicyConfig,
                lateClock
        );

        NormalizedReceipt receipt = createSimulatorReceipt("TX-LATE", "120000");
        PaymentReceiptResult result = lateService.processReceipt(receipt);

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("LATE");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING); // Không đổi thành PAID

        verify(booking, never()).confirmPayment(any(), any());
    }

    @Test
    @DisplayName("5. Tiền vào thiếu (UNDERPAYMENT): lưu UNAPPLIED, không confirm booking")
    void underpaymentReceipt_markedUnderpayment() {
        NormalizedReceipt receipt = createSimulatorReceipt("TX-UNDER", "100000"); // Thiếu 20k

        PaymentReceiptResult result = service.processReceipt(receipt);

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("UNDERPAYMENT");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        verify(booking, never()).confirmPayment(any(), any());
    }

    @Test
    @DisplayName("6. Tiền vào thừa (OVERPAYMENT): lưu UNAPPLIED, không confirm booking")
    void overpaymentReceipt_markedOverpayment() {
        NormalizedReceipt receipt = createSimulatorReceipt("TX-OVER", "150000"); // Thừa 30k

        PaymentReceiptResult result = service.processReceipt(receipt);

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("OVERPAYMENT");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        verify(booking, never()).confirmPayment(any(), any());
    }

    @Test
    @DisplayName("7. Receipt không khớp (UNMATCHED): lưu PaymentTransaction mồ côi với payment = null")
    void unmatchedReceipt_savedWithNullPayment() {
        NormalizedReceipt receipt = new NormalizedReceipt(
                "SIMULATOR",
                "SIMULATOR_ACCOUNT",
                "TX-UNKNOWN",
                new BigDecimal("120000"),
                "VND",
                NOW,
                NOW,
                "VA-UNKNOWN",
                "CODE_NOT_FOUND",
                "content",
                "{}"
        );

        when(paymentRepository.findByPaymentCode("CODE_NOT_FOUND")).thenReturn(Optional.empty());
        when(paymentRepository.findByVaNumber("VA-UNKNOWN")).thenReturn(Optional.empty());

        PaymentReceiptResult result = service.processReceipt(receipt);

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNMATCHED);
        assertThat(result.reason()).isEqualTo("UNMATCHED");

        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPayment()).isNull();
        assertThat(captor.getValue().getApplicationReason()).isEqualTo("UNMATCHED");
    }

    @Test
    @DisplayName("8. BR-PAY-04: Sự kiện thất bại FAILED đến trễ không được hạ cấp đơn đã PAID")
    void lateFailedEvent_doesNotDowngradePaidPayment() {
        NormalizedReceipt receipt = createSimulatorReceipt("TX-001", "120000");
        service.processReceipt(receipt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

        // Giả lập sự kiện FAILED trễ gọi markFailed()
        assertThatThrownBy(() -> payment.markFailed())
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(com.thang.chargeops.exception.errorcode.PaymentErrorCode.STATE_CONFLICT));
    }

    @Test
    @DisplayName("Receipt thiếu VA không được tự điền từ Payment")
    void missingVa_isUnmatchedInsteadOfBeingHarmonized() {
        NormalizedReceipt receipt = new NormalizedReceipt(
                "SIMULATOR", "SIMULATOR_ACCOUNT", "TX-NO-VA",
                new BigDecimal("120000"), "VND", NOW, NOW,
                null, paymentCode, "content", "{}");

        PaymentReceiptResult result = service.processReceipt(receipt);

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNMATCHED);
        assertThat(result.reason()).isEqualTo("INCOMPLETE_ORDER_IDENTITY");
        verify(paymentRepository, never()).findByPaymentCode(anyString());
        verify(connectorRepository, never()).findByIdWithLock(any());
    }

    @Test
    @DisplayName("Kiểm tra duplicate lại sau khi đã khóa Payment")
    void duplicate_isCheckedAgainAfterPaymentLock() {
        NormalizedReceipt receipt = createSimulatorReceipt("TX-DOUBLE-CHECK", "120000");

        service.processReceipt(receipt);

        var order = inOrder(
                paymentTransactionRepository,
                connectorRepository,
                bookingRepository,
                paymentRepository
        );
        order.verify(paymentTransactionRepository)
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", "SIMULATOR_ACCOUNT", "TX-DOUBLE-CHECK");
        order.verify(connectorRepository).findByIdWithLock(connectorId);
        order.verify(bookingRepository).findByIdWithLock(bookingId);
        order.verify(paymentRepository).findByBookingIdWithLock(bookingId);
        order.verify(paymentTransactionRepository)
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", "SIMULATOR_ACCOUNT", "TX-DOUBLE-CHECK");
    }
}
