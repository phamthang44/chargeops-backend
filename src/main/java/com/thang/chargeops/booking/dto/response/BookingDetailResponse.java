package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full Driver-facing booking detail.
 *
 * <p>Command receipts and raw status-history rows are internal audit records and
 * are intentionally absent. A future public timeline must use a separately
 * reviewed, sanitized DTO.</p>
 */
@Builder
public record BookingDetailResponse(
        UUID bookingId,
        String bookingCode,
        BookingStatus status,
        BookingStatus persistedStatus,
        boolean stateReconciliationPending,
        BookingCancellationReason cancellationReason,
        long version,
        StationSnapshotResponse station,
        String timezone,
        Instant startAt,
        Instant endAt,
        int durationMin,
        long totalAmount,
        String currency,
        List<PriceLine> priceLines,
        PriceBasis pricingBasis,
        String policyVersion,
        Instant paymentHoldExpiresAt,
        Instant paymentConfirmedAt,
        Instant freeCancellationDeadline,
        Instant checkInOpensAt,
        Instant checkInDeadline,
        Instant checkedInAt,
        Instant chargingStartedAt,
        Instant completedAt,
        PaymentDetail payment,
        CheckoutDetail checkout,
        List<RefundSummary> refunds,
        BookingActionsResponse actions
) {
    public BookingDetailResponse {
        priceLines = priceLines == null ? List.of() : List.copyOf(priceLines);
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }

    public record PaymentDetail(
            UUID paymentId,
            PaymentStatus status,
            PaymentMethod method,
            long expectedAmount,
            long collectedAmount,
            long appliedToPackageAmount,
            long packageRefundedAmount,
            long excessAmount,
            long unallocatedAmount,
            String currency
    ) {
    }

    public record CheckoutDetail(
            CheckoutState status,
            PaymentMethod method,
            Instant expiresAt,
            String instruction,
            String checkoutReference,
            String checkoutUrl
    ) {
    }

    public enum CheckoutState {
        NOT_CREATED,
        READY,
        UNAVAILABLE,
        EXPIRED
    }

    public record RefundSummary(
            UUID refundId,
            long amount,
            RefundReason reason,
            RefundState status,
            boolean needsReconciliation
    ) {
    }

    public enum RefundReason {
        VOLUNTARY_GRACE,
        STATION_FAILURE,
        EXCESS_PAYMENT,
        LATE_PAYMENT,
        UNAPPLIED_PAYMENT
    }

    public enum RefundState {
        PENDING,
        PROCESSING,
        SUCCEEDED,
        FAILED
    }
}
