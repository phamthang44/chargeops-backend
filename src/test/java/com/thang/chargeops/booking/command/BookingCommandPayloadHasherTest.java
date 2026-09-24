package com.thang.chargeops.booking.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingCommandPayloadHasherTest {

    @Test
    void hashesCallerBuiltCanonicalPayloadDeterministically() {
        String payload = "connectorId=c-1|startAt=2026-09-16T03:00:00Z|durationMin=60|amount=126000";

        assertThat(BookingCommandPayloadHasher.sha256(payload))
                .isEqualTo(BookingCommandPayloadHasher.sha256(payload))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void changingAnAcceptedValueChangesTheHash() {
        String accepted = "connectorId=c-1|durationMin=60|amount=126000";
        String changed = "connectorId=c-1|durationMin=90|amount=126000";

        assertThat(BookingCommandPayloadHasher.sha256(accepted))
                .isNotEqualTo(BookingCommandPayloadHasher.sha256(changed));
    }

    @Test
    void hashesCancelBookingPayloadDeterministically() {
        java.util.UUID bookingId = java.util.UUID.randomUUID();
        CancelBookingCanonicalPayload payload = CancelBookingCanonicalPayload.builder()
                .bookingId(bookingId)
                .expectedVersion(0L)
                .expectedRefundAmount(126000L)
                .acceptedPolicyVersion("booking-v4.9")
                .build();

        assertThat(BookingCommandPayloadHasher.sha256(payload))
                .isEqualTo(BookingCommandPayloadHasher.sha256(payload))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void changingAnyCancelBookingFieldChangesHash() {
        java.util.UUID id1 = java.util.UUID.randomUUID();
        java.util.UUID id2 = java.util.UUID.randomUUID();

        CancelBookingCanonicalPayload base = CancelBookingCanonicalPayload.builder()
                .bookingId(id1)
                .expectedVersion(1L)
                .expectedRefundAmount(126000L)
                .acceptedPolicyVersion("booking-v4.9")
                .build();

        CancelBookingCanonicalPayload diffId = CancelBookingCanonicalPayload.builder()
                .bookingId(id2)
                .expectedVersion(1L)
                .expectedRefundAmount(126000L)
                .acceptedPolicyVersion("booking-v4.9")
                .build();

        CancelBookingCanonicalPayload diffVersion = CancelBookingCanonicalPayload.builder()
                .bookingId(id1)
                .expectedVersion(2L)
                .expectedRefundAmount(126000L)
                .acceptedPolicyVersion("booking-v4.9")
                .build();

        CancelBookingCanonicalPayload diffAmount = CancelBookingCanonicalPayload.builder()
                .bookingId(id1)
                .expectedVersion(1L)
                .expectedRefundAmount(0L)
                .acceptedPolicyVersion("booking-v4.9")
                .build();

        CancelBookingCanonicalPayload diffPolicy = CancelBookingCanonicalPayload.builder()
                .bookingId(id1)
                .expectedVersion(1L)
                .expectedRefundAmount(126000L)
                .acceptedPolicyVersion("booking-v5.0")
                .build();

        String baseHash = BookingCommandPayloadHasher.sha256(base);
        assertThat(BookingCommandPayloadHasher.sha256(diffId)).isNotEqualTo(baseHash);
        assertThat(BookingCommandPayloadHasher.sha256(diffVersion)).isNotEqualTo(baseHash);
        assertThat(BookingCommandPayloadHasher.sha256(diffAmount)).isNotEqualTo(baseHash);
        assertThat(BookingCommandPayloadHasher.sha256(diffPolicy)).isNotEqualTo(baseHash);
    }
}
