package com.thang.chargeops.incident;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentReasonResolverTest {

    private static final UUID INCIDENT_ID = UUID.fromString("a2e2d2e4-67a1-40df-8ccf-e7fdc537f0a8");
    private static final UUID BOOKING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private ConnectorIncidentRepository incidents;

    @Mock
    private IncidentBookingRepository affectedBookings;

    private IncidentReasonResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new IncidentReasonResolver(incidents, affectedBookings);
    }

    @Test
    void resolvesReportReasonFromIncidentTableEvenWhenAuditCopyHasNoDetail() {
        String raw = "INCIDENT_REPORT:" + INCIDENT_ID;
        ConnectorIncident incident = incident();
        when(incident.getReason()).thenReturn("Mất điện nguồn cấp trụ sạc");
        when(incidents.findAllById(any())).thenReturn(List.of(incident));

        Map<String, IncidentReasonResolver.Resolved> resolved = resolver.resolve(List.of(raw));

        var view = resolved.get(raw);
        assertThat(view).isNotNull();
        assertThat(view.action()).isEqualTo(IncidentAuditReason.Action.INCIDENT_REPORT);
        assertThat(view.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(view.detail()).isEqualTo("Mất điện nguồn cấp trụ sạc");
    }

    @Test
    void resolvesRecoveryReasonForRecoveredEvents() {
        String raw = "INCIDENT_RECOVER:" + INCIDENT_ID + " - short copy";
        ConnectorIncident incident = incident();
        when(incident.getRecoveryReason()).thenReturn("Đã cấp lại nguồn cho trụ sạc");
        when(incidents.findAllById(any())).thenReturn(List.of(incident));

        var view = resolver.resolve(List.of(raw)).get(raw);

        assertThat(view.action()).isEqualTo(IncidentAuditReason.Action.INCIDENT_RECOVER);
        assertThat(view.detail()).isEqualTo("Đã cấp lại nguồn cho trụ sạc");
    }

    @Test
    void resolvesSessionStopReasonFromIncidentBookingSnapshot() {
        String raw = "INCIDENT_SESSION_STOP:" + INCIDENT_ID + ":" + BOOKING_ID;
        ConnectorIncident incident = incident();
        when(incidents.findAllById(any())).thenReturn(List.of(incident));
        IncidentBooking affected = mock(IncidentBooking.class);
        when(affected.getResolutionReason()).thenReturn("Dừng phiên đang sạc theo yêu cầu an toàn");
        when(affectedBookings.findByIncidentIdAndBookingId(INCIDENT_ID, BOOKING_ID))
                .thenReturn(Optional.of(affected));

        var view = resolver.resolve(List.of(raw)).get(raw);

        assertThat(view.action()).isEqualTo(IncidentAuditReason.Action.INCIDENT_SESSION_STOP);
        assertThat(view.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(view.detail()).isEqualTo("Dừng phiên đang sạc theo yêu cầu an toàn");
    }

    @Test
    void leavesPlainTextReasonsUnresolved() {
        Map<String, IncidentReasonResolver.Resolved> resolved =
                resolver.resolve(java.util.Arrays.asList("Scheduled maintenance", null, ""));

        assertThat(resolved).isEmpty();
    }

    private ConnectorIncident incident() {
        ConnectorIncident incident = mock(ConnectorIncident.class);
        when(incident.getId()).thenReturn(INCIDENT_ID);
        return incident;
    }
}
