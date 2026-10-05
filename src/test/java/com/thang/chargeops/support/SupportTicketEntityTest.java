package com.thang.chargeops.support;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SupportTicketEntityTest {

    @Test
    void resolutionCycleUsesExactTenDaysAndContinuationCancelsOldDeadline() {
        UserProfile reporter = mock(UserProfile.class);
        UserProfile handler = mock(UserProfile.class);
        SupportTicket ticket = SupportTicket.open("TKT-20261001-0001", TicketCategory.OTHER,
            TicketPriority.MEDIUM, reporter, null, null, new SupportTicket.TicketDetails("Issue", "Description"));
        Instant first = Instant.parse("2026-10-01T00:00:00Z");

        ticket.assign(handler);
        ticket.resolve(first);
        assertThat(ticket.getAutoCloseAt()).isEqualTo(first.plusSeconds(10L * 24 * 60 * 60));
        assertThat(ticket.getResolutionCycle()).isEqualTo(1);

        ticket.continueWork(handler);
        assertThat(ticket.getAutoCloseAt()).isNull();
        ticket.resolve(first.plusSeconds(3600));
        assertThat(ticket.getResolutionCycle()).isEqualTo(2);
        assertThat(ticket.getAutoCloseAt()).isEqualTo(first.plusSeconds(3600 + 10L * 24 * 60 * 60));

        ticket.close("AUTO_CLOSED_NO_RESPONSE");
        assertThat(ticket.getAutoCloseAt()).isNull();
        assertThat(ticket.getCloseReason()).isEqualTo("AUTO_CLOSED_NO_RESPONSE");
        assertThatThrownBy(() -> ticket.assign(handler)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsOpenTicketAndImmutableAuditRecords() {
        UserProfile reporter = mock(UserProfile.class);
        Station station = mock(Station.class);
        Booking booking = mock(Booking.class);
        SupportTicket ticket = SupportTicket.open(
                " TKT-20260929-0001 ",
                TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH,
                reporter,
                station,
                booking,
                new SupportTicket.TicketDetails(" Charger stopped ",
                        " The charging connector stopped unexpectedly. ")
        );

        assertThat(ticket.getTicketCode()).isEqualTo("TKT-20260929-0001");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getSubject()).isEqualTo("Charger stopped");
        assertThat(ticket.getDescription()).isEqualTo("The charging connector stopped unexpectedly.");

        Instant affectedAt = Instant.parse("2026-09-29T08:00:00Z");
        Instant recordedAt = affectedAt.plusSeconds(60);
        TicketMessage message = TicketMessage.create(
                ticket, reporter, TicketActorKind.REPORTER, " Initial report ", affectedAt
        );
        TicketFinding finding = TicketFinding.record(
                ticket,
                booking,
                TicketFindingConclusion.STATION_FAILURE,
                affectedAt,
                " Connector hardware fault ",
                recordedAt,
                reporter
        );

        assertThat(message.getBody()).isEqualTo("Initial report");
        assertThat(message.getCreatedAt()).isEqualTo(affectedAt);
        assertThat(finding.getReason()).isEqualTo("Connector hardware fault");
        assertThat(finding.getAffectedAt()).isEqualTo(affectedAt);
        assertThat(finding.getRecordedAt()).isEqualTo(recordedAt);
    }

    @Test
    void rejectsMalformedCodesBlankAuditTextAndFutureAffectedTime() {
        UserProfile actor = mock(UserProfile.class);
        Booking booking = mock(Booking.class);

        assertThatThrownBy(() -> SupportTicket.open(
                "TKT-bad",
                TicketCategory.OTHER,
                TicketPriority.MEDIUM,
                actor,
                null,
                null,
                new SupportTicket.TicketDetails("Subject", "Description")
        )).isInstanceOf(IllegalArgumentException.class);

        SupportTicket ticket = SupportTicket.open(
                "TKT-20260929-0002",
                TicketCategory.OTHER,
                TicketPriority.MEDIUM,
                actor,
                null,
                null,
                new SupportTicket.TicketDetails("Subject", "Description")
        );
        Instant now = Instant.parse("2026-09-29T08:00:00Z");

        assertThatThrownBy(() -> TicketMessage.create(
                ticket, actor, TicketActorKind.REPORTER, " ", now
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketFinding.record(
                ticket,
                booking,
                TicketFindingConclusion.STATION_FAILURE,
                now.plusSeconds(1),
                "Reason",
                now,
                actor
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
