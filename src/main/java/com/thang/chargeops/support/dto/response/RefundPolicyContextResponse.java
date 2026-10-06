package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.booking.dto.response.ServiceFailureContextResponse;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.common.enums.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Exact case scope; no Owner ledger or private evidence dossier. */
public record RefundPolicyContextResponse(
        UUID ticketId, UUID escalationId, long ticketVersion, boolean active,
        ServiceFailureContextResponse booking,
        ServiceFailureContextResponse.Availability grant,
        ServiceFailureContextResponse.Availability insufficient,
        String bookingCode, long packageAmountVnd,
        Dossier dossier) {

    /**
     * Booking and payment facts of this case only, derived from the ticket relation.
     * Carries no evidence hash, no profile identifiers and no record of another case.
     */
    public record Dossier(
            String currency,
            Window window,
            Snapshot snapshot,
            Policy policy,
            Timeline timeline,
            Payment payment,
            Receipt receipt,
            List<PriceLine> priceLines) {

        public record Window(Instant startAt, Instant endAt, int durationMin) {}

        public record Snapshot(
                String stationName, String stationAddress, String chargePointCode, String connectorCode) {}

        public record Policy(
                String version,
                Integer cancellationGraceMin,
                Integer checkInCloseBeforeEndMin,
                Integer stationFailureRefundPercent,
                Integer voluntaryRefundPercent) {}

        public record Timeline(
                Instant expiresAt,
                Instant paymentConfirmedAt,
                Instant freeCancellationDeadline,
                Instant checkInDeadline,
                Instant checkedInAt,
                Instant chargingStartedAt,
                Instant completedAt,
                Instant cancelledAt,
                String cancellationReason) {}

        public record Payment(
                PaymentStatus status, long amount, Instant paidAt, PaymentEnvironment environment,
                boolean needsReconciliation) {}

        public record Receipt(
                UUID receiptId,
                String transactionRef,
                long amount,
                String currency,
                Instant receivedAt,
                PaymentApplicationClassification classification) {}

        public record PriceLine(String label, String periodCode, int durationMin, long amount) {}
    }
}
