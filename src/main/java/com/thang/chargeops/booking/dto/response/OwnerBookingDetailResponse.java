package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;
import com.thang.chargeops.common.enums.BookingStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
public record OwnerBookingDetailResponse(
        UUID bookingId, String bookingCode, BookingStatus status, BookingStatus persistedStatus,
        boolean stateReconciliationPending, BookingCancellationReason cancellationReason,
        long version, String driverDisplayName, StationSnapshotResponse station, String timezone,
        Instant startAt, Instant endAt, int durationMin, long totalAmount, String currency,
        List<PriceLine> priceLines, PriceBasis pricingBasis, String policyVersion,
        Instant paymentHoldExpiresAt, Instant paymentConfirmedAt, Instant freeCancellationDeadline,
        Instant checkInOpensAt, Instant checkInDeadline, Instant checkedInAt,
        Instant chargingStartedAt, Instant completedAt, Instant createdAt,
        BookingDetailResponse.PaymentDetail payment, BookingDetailResponse.CheckoutDetail checkout,
        List<BookingDetailResponse.RefundSummary> refunds, OwnerActionsResponse actions
) {
    public OwnerBookingDetailResponse {
        priceLines = priceLines == null ? List.of() : List.copyOf(priceLines);
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }
}
