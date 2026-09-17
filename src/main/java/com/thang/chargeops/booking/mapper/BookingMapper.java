package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.dto.response.BookingActionsResponse;
import com.thang.chargeops.booking.dto.response.BookingCancellationReason;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.DriverBookingListItemResponse;
import com.thang.chargeops.booking.dto.response.StationSnapshotResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.booking.service.model.DriverBookingCapabilities;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Hand-written mapper for Booking responses.
 * Driver-facing state and capability rules are delegated to the read policy.
 */
@Component
@RequiredArgsConstructor
public class BookingMapper {

    private final DriverBookingReadPolicy driverReadPolicy;

    public CreateBookingResponse toCreateBookingResponse(
            Booking booking,
            Payment payment
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(payment, "payment must not be null");

        return CreateBookingResponse.builder()
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .status(booking.getStatus())
                .version(requireVersion(booking))
                .connectorId(booking.getConnector().getId())
                .startAt(booking.getStartAt())
                .endAt(booking.getEndAt())
                .durationMin(durationMinutes(booking))
                .totalAmount(amount(booking.getTotalAmount()))
                .currency(payment.getCurrency())
                .paymentHoldExpiresAt(booking.getExpiresAt())
                .payment(new CreateBookingResponse.PaymentSummary(
                        payment.getId(),
                        payment.getStatus(),
                        payment.getMethod()
                ))
                .checkout(new CreateBookingResponse.CheckoutSummary(
                        CheckoutStatus.NOT_CREATED
                ))
                .build();
    }

    /** Simple service-layer entry point for booking list responses. */
    public DriverBookingListItemResponse toDriverBookingListItemResponse(
            Booking booking,
            Instant evaluatedAt
    ) {
        BookingReadSnapshot snapshot = driverReadPolicy.snapshotForList(
                booking,
                evaluatedAt
        );
        return toDriverBookingListItemResponse(booking, snapshot);
    }

    /** Entry point for callers that already aggregated request-time values. */
    public DriverBookingListItemResponse toDriverBookingListItemResponse(
            Booking booking,
            BookingReadSnapshot snapshot
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        BookingReadEvaluation evaluation = driverReadPolicy.evaluate(
                booking,
                snapshot
        );

        return DriverBookingListItemResponse.builder()
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .status(evaluation.effectiveStatus())
                .persistedStatus(booking.getStatus())
                .stateReconciliationPending(
                        evaluation.stateReconciliationPending()
                )
                .cancellationReason(toCancellationReason(
                        evaluation.cancellationReason()
                ))
                .version(requireVersion(booking))
                .station(toStationSnapshot(booking))
                .timezone(timezone(booking))
                .startAt(booking.getStartAt())
                .endAt(booking.getEndAt())
                .durationMin(durationMinutes(booking))
                .totalAmount(amount(booking.getTotalAmount()))
                .currency(snapshot.currency())
                .paymentHoldExpiresAt(booking.getExpiresAt())
                .freeCancellationDeadline(
                        booking.getFreeCancellationDeadline()
                )
                .checkInOpensAt(booking.getStartAt())
                .checkInDeadline(booking.getCheckInDeadline())
                .checkedInAt(booking.getCheckedInAt())
                .chargingStartedAt(booking.getChargingStartedAt())
                .createdAt(booking.getCreatedAt())
                .actions(toActions(evaluation.capabilities()))
                .build();
    }

    public BookingDetailResponse toBookingDetailResponse(
            Booking booking,
            Payment payment,
            BookingReadSnapshot snapshot
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(payment, "payment must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        BookingReadEvaluation evaluation = driverReadPolicy.evaluate(
                booking,
                snapshot
        );

        return BookingDetailResponse.builder()
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .status(evaluation.effectiveStatus())
                .persistedStatus(booking.getStatus())
                .stateReconciliationPending(
                        evaluation.stateReconciliationPending()
                )
                .cancellationReason(toCancellationReason(
                        evaluation.cancellationReason()
                ))
                .version(requireVersion(booking))
                .station(toStationSnapshot(booking))
                .timezone(timezone(booking))
                .startAt(booking.getStartAt())
                .endAt(booking.getEndAt())
                .durationMin(durationMinutes(booking))
                .totalAmount(amount(booking.getTotalAmount()))
                .currency(snapshot.currency())
                .priceLines(toPriceLines(booking))
                .pricingBasis(toPricingBasis(booking))
                .policyVersion(booking.getPolicyVersion())
                .paymentHoldExpiresAt(booking.getExpiresAt())
                .paymentConfirmedAt(booking.getPaymentConfirmedAt())
                .freeCancellationDeadline(
                        booking.getFreeCancellationDeadline()
                )
                .checkInOpensAt(booking.getStartAt())
                .checkInDeadline(booking.getCheckInDeadline())
                .checkedInAt(booking.getCheckedInAt())
                .chargingStartedAt(booking.getChargingStartedAt())
                .completedAt(booking.getCompletedAt())
                .createdAt(booking.getCreatedAt())
                .payment(toPaymentDetail(payment, snapshot))
                .checkout(snapshot.checkout())
                .refunds(snapshot.refunds())
                .actions(toActions(evaluation.capabilities()))
                .build();
    }

    public BookingActionsResponse toActions(
            DriverBookingCapabilities capabilities
    ) {
        Objects.requireNonNull(
                capabilities,
                "capabilities must not be null"
        );
        return BookingActionsResponse.builder()
                .canCancel(capabilities.canCancel())
                .refundableAmount(capabilities.refundableAmount())
                .cancellationReason(
                        BookingActionsResponse.CancellationCapabilityReason
                                .valueOf(
                                        capabilities.cancellationReason().name()
                                )
                )
                .canCheckIn(capabilities.canCheckIn())
                .checkInReason(
                        BookingActionsResponse.CheckInCapabilityReason.valueOf(
                                capabilities.checkInReason().name()
                        )
                )
                .canStartCharging(capabilities.canStartCharging())
                .canComplete(capabilities.canComplete())
                .canReportIssue(capabilities.canReportIssue())
                .build();
    }

    private BookingCancellationReason toCancellationReason(
            BookingReadEvaluation.CancellationReason reason
    ) {
        return reason == null
                ? null
                : BookingCancellationReason.valueOf(reason.name());
    }

    public StationSnapshotResponse toStationSnapshot(Booking booking) {
        Connector connector = Objects.requireNonNull(
                booking.getConnector(),
                "booking connector must not be null"
        );
        ChargePoint chargePoint = Objects.requireNonNull(
                connector.getChargePoint(),
                "connector charge point must not be null"
        );
        Station station = Objects.requireNonNull(
                chargePoint.getStation(),
                "charge point station must not be null"
        );

        return StationSnapshotResponse.builder()
                .stationId(station.getId())
                .stationName(booking.getStationNameSnapshot())
                .stationAddress(booking.getStationAddressSnapshot())
                .chargePointCode(booking.getChargePointCodeSnapshot())
                .connectorId(connector.getId())
                .connectorCode(booking.getConnectorCodeSnapshot())
                .build();
    }

    public List<PriceLine> toPriceLines(Booking booking) {
        return booking.getPriceLines().stream()
                .sorted(Comparator.comparing(BookingPriceLine::getSequence))
                .map(this::toPriceLine)
                .toList();
    }

    public PriceLine toPriceLine(BookingPriceLine line) {
        Objects.requireNonNull(line, "price line must not be null");
        return new PriceLine(
                line.getSequence(),
                line.getSegmentStart(),
                line.getSegmentEnd(),
                line.getDurationMinutes(),
                line.getLabel(),
                TouRatePeriodCode.valueOf(line.getPeriodCode()),
                line.getRateVndPerKwh(),
                line.getEstimatedEnergyKwh(),
                amount(line.getAmount())
        );
    }

    public PriceBasis toPricingBasis(Booking booking) {
        return booking.getPriceLines().stream()
                .min(Comparator.comparing(BookingPriceLine::getSequence))
                .map(line -> new PriceBasis(
                        PriceBasis.FIXED_PACKAGE_KIND,
                        PriceBasis.VND_PER_KWH_RATE_UNIT,
                        line.getFormulaVersion(),
                        line.getEnergyFactor(),
                        line.getPowerKw(),
                        PriceBasis.ENERGY_DECIMAL_PLACES,
                        PriceBasis.LINE_AMOUNT_ROUNDING_VND,
                        PriceBasis.ROUNDING_MODE
                ))
                .orElse(null);
    }

    public BookingDetailResponse.PaymentDetail toPaymentDetail(
            Payment payment,
            BookingReadSnapshot snapshot
    ) {
        return new BookingDetailResponse.PaymentDetail(
                payment.getId(),
                payment.getStatus(),
                payment.getMethod(),
                amount(payment.getAmount()),
                snapshot.collectedAmount(),
                snapshot.appliedToPackageAmount(),
                snapshot.packageRefundedAmount(),
                snapshot.excessAmount(),
                snapshot.unallocatedAmount(),
                snapshot.currency()
        );
    }

    private String timezone(Booking booking) {
        BookingPolicySnapshot policy = booking.getPolicySnapshot();
        return policy == null ? null : policy.timezone();
    }

    private int durationMinutes(Booking booking) {
        return Math.toIntExact(Duration.between(
                booking.getStartAt(),
                booking.getEndAt()
        ).toMinutes());
    }

    private long requireVersion(Booking booking) {
        return Objects.requireNonNull(
                booking.getVersion(),
                "booking version must not be null"
        );
    }

    private long amount(BigDecimal value) {
        return Objects.requireNonNull(
                value,
                "amount must not be null"
        ).longValueExact();
    }
}
