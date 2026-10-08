package com.thang.chargeops.incident;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentAuditReasonTest {

    private static final UUID INCIDENT_ID = UUID.fromString("a2e2d2e4-67a1-40df-8ccf-e7fdc537f0a8");
    private static final UUID BOOKING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void parsesLegacyReportReasonWithoutEmbeddedDetail() {
        var reference = IncidentAuditReason.parse("INCIDENT_REPORT:" + INCIDENT_ID).orElseThrow();

        assertThat(reference.action()).isEqualTo(IncidentAuditReason.Action.INCIDENT_REPORT);
        assertThat(reference.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(reference.bookingId()).isNull();
        assertThat(reference.embeddedDetail()).isNull();
    }

    @Test
    void parsesReportReasonWithEmbeddedDetail() {
        var reference = IncidentAuditReason
                .parse("INCIDENT_REPORT:" + INCIDENT_ID + " - Mất điện nguồn cấp trụ sạc")
                .orElseThrow();

        assertThat(reference.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(reference.embeddedDetail()).isEqualTo("Mất điện nguồn cấp trụ sạc");
    }

    @Test
    void parsesSessionStopReasonWithBookingId() {
        var reference = IncidentAuditReason
                .parse("INCIDENT_SESSION_STOP:" + INCIDENT_ID + ":" + BOOKING_ID + " - Dừng phiên đang sạc")
                .orElseThrow();

        assertThat(reference.action()).isEqualTo(IncidentAuditReason.Action.INCIDENT_SESSION_STOP);
        assertThat(reference.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(reference.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(reference.embeddedDetail()).isEqualTo("Dừng phiên đang sạc");
    }

    @Test
    void ignoresPlainTextAndMalformedReasons() {
        assertThat(IncidentAuditReason.parse("Cable replacement")).isEmpty();
        assertThat(IncidentAuditReason.parse("INCIDENT_REPORT:not-a-uuid")).isEmpty();
        assertThat(IncidentAuditReason.parse(null)).isEmpty();
    }

    @Test
    void formattedReasonRoundTripsAndStaysWithinColumnLimit() {
        String detail = "Mất điện ".repeat(100);
        String raw = IncidentAuditReason.format(IncidentAuditReason.Action.INCIDENT_REPORT, INCIDENT_ID, null, detail);

        assertThat(raw).hasSize(500);
        var reference = IncidentAuditReason.parse(raw).orElseThrow();
        assertThat(reference.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(reference.embeddedDetail()).isEqualTo(raw.substring(raw.indexOf(" - ") + 3).trim());
    }

    @Test
    void formattedSessionStopIncludesBookingId() {
        String raw = IncidentAuditReason.format(
                IncidentAuditReason.Action.INCIDENT_SESSION_STOP, INCIDENT_ID, BOOKING_ID, "An toàn");

        assertThat(raw).isEqualTo("INCIDENT_SESSION_STOP:" + INCIDENT_ID + ":" + BOOKING_ID + " - An toàn");
    }
}
