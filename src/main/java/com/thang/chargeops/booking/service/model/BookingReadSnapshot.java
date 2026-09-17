package com.thang.chargeops.booking.service.model;

import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Request-time values needed to build Driver booking responses.
 *
 * <p>The booking entity remains the source of immutable booking snapshots.
 * Values aggregated from payment/refund/checkout data are supplied here so the
 * mapper stays deterministic and never performs repository calls.</p>
 */
@Builder
public record BookingReadSnapshot(
        Instant evaluatedAt,
        boolean stationAvailable,
        boolean canReportIssue,
        String currency,
        long collectedAmount,
        long appliedToPackageAmount,
        long packageRefundedAmount,
        long excessAmount,
        long unallocatedAmount,
        BookingDetailResponse.CheckoutDetail checkout,
        List<BookingDetailResponse.RefundSummary> refunds
) {
    public static final String DEFAULT_CURRENCY = "VND";

    public BookingReadSnapshot {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        currency = currency == null || currency.isBlank()
                ? DEFAULT_CURRENCY
                : currency;
        requireNonNegative(collectedAmount, "collectedAmount");
        requireNonNegative(appliedToPackageAmount, "appliedToPackageAmount");
        requireNonNegative(packageRefundedAmount, "packageRefundedAmount");
        requireNonNegative(excessAmount, "excessAmount");
        requireNonNegative(unallocatedAmount, "unallocatedAmount");
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }

    public static BookingReadSnapshot forList(
            Instant evaluatedAt,
            boolean stationAvailable
    ) {
        return BookingReadSnapshot.builder()
                .evaluatedAt(evaluatedAt)
                .stationAvailable(stationAvailable)
                .canReportIssue(true)
                .currency(DEFAULT_CURRENCY)
                .build();
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
