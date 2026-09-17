package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.dto.response.BookingActionsResponse;
import com.thang.chargeops.booking.dto.response.BookingCancellationReason;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.DriverBookingListItemResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingMapperTest {

    private final BookingMapper mapper = new BookingMapper(
            new DriverBookingReadPolicy()
    );

    @Test
    void mapsCreateResponseFromBookingAndPayment() {
        UUID bookingId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-16T03:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T04:30:00Z");
        Instant expiresAt = Instant.parse("2026-09-16T02:10:00Z");
        Booking booking = mock(Booking.class);
        Connector connector = mock(Connector.class);
        Payment payment = mock(Payment.class);

        when(booking.getId()).thenReturn(bookingId);
        when(booking.getBookingCode()).thenReturn("BK-12345678901234567890");
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getVersion()).thenReturn(0L);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getId()).thenReturn(connectorId);
        when(booking.getStartAt()).thenReturn(startAt);
        when(booking.getEndAt()).thenReturn(endAt);
        when(booking.getTotalAmount()).thenReturn(new BigDecimal("126000.00"));
        when(booking.getExpiresAt()).thenReturn(expiresAt);
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getMethod()).thenReturn(PaymentMethod.SIMULATOR);
        when(payment.getCurrency()).thenReturn("VND");

        CreateBookingResponse response = mapper.toCreateBookingResponse(booking, payment);

        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.connectorId()).isEqualTo(connectorId);
        assertThat(response.durationMin()).isEqualTo(90);
        assertThat(response.totalAmount()).isEqualTo(126_000L);
        assertThat(response.paymentHoldExpiresAt()).isEqualTo(expiresAt);
        assertThat(response.payment().paymentId()).isEqualTo(paymentId);
        assertThat(response.checkout().status()).isEqualTo(CheckoutStatus.NOT_CREATED);
    }

    @Test
    void mapsImmutableStationSnapshotsAndExpiresPendingAtDeadline() {
        Instant startAt = Instant.parse("2026-09-17T03:00:00Z");
        Instant endAt = Instant.parse("2026-09-17T04:30:00Z");
        Instant expiresAt = Instant.parse("2026-09-17T02:10:00Z");
        Booking booking = bookingWithRelations(
                BookingStatus.PENDING,
                startAt,
                endAt
        );
        when(booking.getExpiresAt()).thenReturn(expiresAt);

        DriverBookingListItemResponse response =
                mapper.toDriverBookingListItemResponse(
                        booking,
                        BookingReadSnapshot.forList(expiresAt, true)
                );

        assertThat(response.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(response.persistedStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.stateReconciliationPending()).isTrue();
        assertThat(response.durationMin()).isEqualTo(90);
        assertThat(response.station().stationName()).isEqualTo("Snapshot Station");
        assertThat(response.station().stationAddress()).isEqualTo("Snapshot Address");
        assertThat(response.station().chargePointCode()).isEqualTo("CP-SNAPSHOT");
        assertThat(response.station().connectorCode()).isEqualTo("CN-SNAPSHOT");
        assertThat(response.actions().canCancel()).isFalse();
    }

    @Test
    void evaluatesConfirmedActionsWithinGraceAndCheckInWindow() {
        Instant startAt = Instant.parse("2026-09-17T03:00:00Z");
        Instant evaluatedAt = Instant.parse("2026-09-17T03:05:00Z");
        Booking booking = bookingWithRelations(
                BookingStatus.CONFIRMED,
                startAt,
                Instant.parse("2026-09-17T04:00:00Z")
        );
        when(booking.getFreeCancellationDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:10:00Z"));
        when(booking.getCheckInDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:45:00Z"));

        BookingReadSnapshot snapshot = BookingReadSnapshot.builder()
                .evaluatedAt(evaluatedAt)
                .stationAvailable(true)
                .canReportIssue(true)
                .packageRefundedAmount(26_000L)
                .build();

        DriverBookingListItemResponse response =
                mapper.toDriverBookingListItemResponse(booking, snapshot);

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.actions().canCancel()).isTrue();
        assertThat(response.actions().refundableAmount()).isEqualTo(100_000L);
        assertThat(response.actions().cancellationReason()).isEqualTo(
                BookingActionsResponse.CancellationCapabilityReason.WITHIN_GRACE
        );
        assertThat(response.actions().canCheckIn()).isTrue();
        assertThat(response.actions().checkInReason()).isEqualTo(
                BookingActionsResponse.CheckInCapabilityReason.AVAILABLE
        );
    }

    @Test
    void evaluatesConfirmedBookingAsNoShowAtCheckInDeadline() {
        Instant startAt = Instant.parse("2026-09-17T03:00:00Z");
        Instant checkInDeadline = Instant.parse("2026-09-17T03:45:00Z");
        Booking booking = bookingWithRelations(
                BookingStatus.CONFIRMED,
                startAt,
                Instant.parse("2026-09-17T04:00:00Z")
        );
        when(booking.getCheckInDeadline()).thenReturn(checkInDeadline);

        DriverBookingListItemResponse response =
                mapper.toDriverBookingListItemResponse(
                        booking,
                        BookingReadSnapshot.forList(checkInDeadline, true)
                );

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.cancellationReason())
                .isEqualTo(BookingCancellationReason.NO_SHOW);
        assertThat(response.stateReconciliationPending()).isTrue();
        assertThat(response.actions().canCheckIn()).isFalse();
        assertThat(response.actions().checkInReason()).isEqualTo(
                BookingActionsResponse.CheckInCapabilityReason.WINDOW_CLOSED
        );
        assertThat(response.actions().canCancel()).isFalse();
    }

    @Test
    void mapsDetailFromEntitiesAndCalculatedFinancialSnapshot() {
        Booking booking = bookingWithRelations(
                BookingStatus.COMPLETED,
                Instant.parse("2026-09-17T03:00:00Z"),
                Instant.parse("2026-09-17T04:00:00Z")
        );
        BookingPriceLine priceLine = BookingPriceLine.builder()
                .sequence(1)
                .segmentStart(Instant.parse("2026-09-17T03:00:00Z"))
                .segmentEnd(Instant.parse("2026-09-17T04:00:00Z"))
                .durationMinutes(60)
                .label("Normal")
                .periodCode("NORMAL")
                .rateVndPerKwh(new BigDecimal("3000.00"))
                .estimatedEnergyKwh(new BigDecimal("37.200"))
                .powerKw(new BigDecimal("60.00"))
                .energyFactor(new BigDecimal("0.620000"))
                .formulaVersion("booking-estimate-v1")
                .amount(new BigDecimal("126000.00"))
                .build();
        when(booking.getPriceLines()).thenReturn(List.of(priceLine));
        when(booking.getPolicyVersion()).thenReturn("booking-v4.9");

        Payment payment = mock(Payment.class);
        UUID paymentId = UUID.randomUUID();
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getStatus()).thenReturn(PaymentStatus.PAID);
        when(payment.getMethod()).thenReturn(PaymentMethod.BANK_TRANSFER);
        when(payment.getAmount()).thenReturn(new BigDecimal("126000.00"));

        BookingDetailResponse.CheckoutDetail checkout =
                new BookingDetailResponse.CheckoutDetail(
                        BookingDetailResponse.CheckoutState.READY,
                        PaymentMethod.BANK_TRANSFER,
                        Instant.parse("2026-09-17T02:10:00Z"),
                        "Transfer to the virtual account",
                        "ORDER-1",
                        null
                );
        BookingReadSnapshot snapshot = BookingReadSnapshot.builder()
                .evaluatedAt(Instant.parse("2026-09-17T05:00:00Z"))
                .stationAvailable(false)
                .canReportIssue(true)
                .collectedAmount(126_000L)
                .appliedToPackageAmount(126_000L)
                .packageRefundedAmount(0L)
                .checkout(checkout)
                .refunds(List.of())
                .build();

        BookingDetailResponse response = mapper.toBookingDetailResponse(
                booking,
                payment,
                snapshot
        );

        assertThat(response.priceLines()).hasSize(1);
        assertThat(response.priceLines().getFirst().amount()).isEqualTo(126_000L);
        assertThat(response.pricingBasis().powerKw())
                .isEqualByComparingTo("60.00");
        assertThat(response.payment().paymentId()).isEqualTo(paymentId);
        assertThat(response.payment().collectedAmount()).isEqualTo(126_000L);
        assertThat(response.checkout()).isEqualTo(checkout);
        assertThat(response.actions().canComplete()).isFalse();
        assertThat(response.actions().canReportIssue()).isTrue();
    }

    private Booking bookingWithRelations(
            BookingStatus status,
            Instant startAt,
            Instant endAt
    ) {
        Booking booking = mock(Booking.class);
        Connector connector = mock(Connector.class);
        ChargePoint chargePoint = mock(ChargePoint.class);
        Station station = mock(Station.class);

        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getBookingCode()).thenReturn("BK-12345678901234567890");
        when(booking.getStatus()).thenReturn(status);
        when(booking.getVersion()).thenReturn(3L);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getId()).thenReturn(UUID.randomUUID());
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getId()).thenReturn(UUID.randomUUID());
        when(booking.getStationNameSnapshot()).thenReturn("Snapshot Station");
        when(booking.getStationAddressSnapshot()).thenReturn("Snapshot Address");
        when(booking.getChargePointCodeSnapshot()).thenReturn("CP-SNAPSHOT");
        when(booking.getConnectorCodeSnapshot()).thenReturn("CN-SNAPSHOT");
        when(booking.getStartAt()).thenReturn(startAt);
        when(booking.getEndAt()).thenReturn(endAt);
        when(booking.getTotalAmount()).thenReturn(new BigDecimal("126000.00"));
        when(booking.getPolicySnapshot()).thenReturn(new BookingPolicySnapshot(
                "booking-v4.9",
                "Asia/Ho_Chi_Minh",
                30,
                List.of(0, 1),
                15,
                30,
                15,
                10,
                10,
                15,
                0,
                100,
                0,
                10
        ));
        when(booking.getPriceLines()).thenReturn(List.of());
        return booking;
    }
}
