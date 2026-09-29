package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.station.entity.Connector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class OwnerBookingMapper {
    private final BookingEffectiveStatePolicy effectiveStatePolicy;
    private final BookingMapper bookingMapper;

    public OwnerBookingListItemResponse toListItem(Booking booking, Instant evaluatedAt) {
        var state = effectiveStatePolicy.evaluate(booking, evaluatedAt);
        Connector connector = booking.getConnector();
        return OwnerBookingListItemResponse.builder()
                .bookingId(booking.getId()).bookingCode(booking.getBookingCode())
                .status(state.effectiveStatus()).persistedStatus(booking.getStatus())
                .stateReconciliationPending(state.stateReconciliationPending())
                .cancellationReason(reason(state.cancellationReason()))
                .stationId(connector.getChargePoint().getStation().getId())
                .stationName(booking.getStationNameSnapshot())
                .connectorId(connector.getId()).connectorCode(booking.getConnectorCodeSnapshot())
                .driverDisplayName(driverName(booking)).startAt(booking.getStartAt()).endAt(booking.getEndAt())
                .checkInDeadline(booking.getCheckInDeadline()).checkedInAt(booking.getCheckedInAt())
                .totalAmount(booking.getTotalAmount().longValueExact()).currency(BookingReadSnapshot.DEFAULT_CURRENCY)
                .build();
    }

    public OperationalBookingResponse toOperational(Booking booking, Instant evaluatedAt) {
        var state = effectiveStatePolicy.evaluate(booking, evaluatedAt);
        Connector connector = booking.getConnector();
        return OperationalBookingResponse.builder()
                .bookingId(booking.getId()).bookingCode(booking.getBookingCode())
                .status(state.effectiveStatus()).cancellationReason(reason(state.cancellationReason()))
                .stationId(connector.getChargePoint().getStation().getId()).connectorId(connector.getId())
                .connectorCode(booking.getConnectorCodeSnapshot()).driverDisplayName(driverName(booking))
                .startAt(booking.getStartAt()).endAt(booking.getEndAt())
                .checkInDeadline(booking.getCheckInDeadline()).checkedInAt(booking.getCheckedInAt()).build();
    }

    public OwnerBookingDetailResponse toDetail(Booking booking, Payment payment,
                                                BookingReadSnapshot snapshot, Instant evaluatedAt) {
        var state = effectiveStatePolicy.evaluate(booking, evaluatedAt);
        return OwnerBookingDetailResponse.builder()
                .bookingId(booking.getId()).bookingCode(booking.getBookingCode())
                .status(state.effectiveStatus()).persistedStatus(booking.getStatus())
                .stateReconciliationPending(state.stateReconciliationPending())
                .cancellationReason(reason(state.cancellationReason()))
                .version(Objects.requireNonNull(booking.getVersion())).driverDisplayName(driverName(booking))
                .station(bookingMapper.toStationSnapshot(booking)).timezone(timezone(booking))
                .startAt(booking.getStartAt()).endAt(booking.getEndAt())
                .durationMin(Math.toIntExact(Duration.between(booking.getStartAt(), booking.getEndAt()).toMinutes()))
                .totalAmount(booking.getTotalAmount().longValueExact()).currency(snapshot.currency())
                .priceLines(bookingMapper.toPriceLines(booking)).pricingBasis(bookingMapper.toPricingBasis(booking))
                .policyVersion(booking.getPolicyVersion()).paymentHoldExpiresAt(booking.getExpiresAt())
                .paymentConfirmedAt(booking.getPaymentConfirmedAt())
                .freeCancellationDeadline(booking.getFreeCancellationDeadline())
                .checkInOpensAt(booking.getStartAt()).checkInDeadline(booking.getCheckInDeadline())
                .checkedInAt(booking.getCheckedInAt()).chargingStartedAt(booking.getChargingStartedAt())
                .completedAt(booking.getCompletedAt()).createdAt(booking.getCreatedAt())
                .payment(bookingMapper.toPaymentDetail(payment, snapshot)).checkout(snapshot.checkout())
                .refunds(snapshot.refunds()).actions(actions(state.effectiveStatus())).build();
    }

    private OwnerActionsResponse actions(BookingStatus status) {
        boolean active = status == BookingStatus.CONFIRMED || status == BookingStatus.CHECKED_IN
                || status == BookingStatus.CHARGING;
        boolean incident = active || status == BookingStatus.COMPLETED;
        return new OwnerActionsResponse(active, true, incident);
    }

    private String driverName(Booking booking) {
        String name = booking.getDriver().getDisplayName();
        return name == null || name.isBlank() ? booking.getDriver().getEmail() : name;
    }

    private String timezone(Booking booking) {
        BookingPolicySnapshot policy = booking.getPolicySnapshot();
        return policy == null ? null : policy.timezone();
    }

    private BookingCancellationReason reason(BookingReadEvaluation.CancellationReason reason) {
        return reason == null ? null : BookingCancellationReason.valueOf(reason.name());
    }
}
