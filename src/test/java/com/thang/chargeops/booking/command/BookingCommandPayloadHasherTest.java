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
}
