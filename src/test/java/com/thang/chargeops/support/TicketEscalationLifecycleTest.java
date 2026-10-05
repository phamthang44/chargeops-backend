package com.thang.chargeops.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TicketEscalationLifecycleTest {
    @Test
    void adminClosureSkipsReporterConfirmationAndRecordsDistinctCloseReason() {
        var ticket = SupportTicket.open("TKT-20261003-9001", TicketCategory.CHARGING_ISSUE,
                TicketPriority.MEDIUM, mock(UserProfile.class), mock(Station.class), null,
                new SupportTicket.TicketDetails("Connector issue", "Connector unavailable"));
        ticket.closeEscalatedSupportCase();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.getCloseReason()).isEqualTo("ADMIN_SUPPORT_CASE_CLOSED");
        assertThat(ticket.getAutoCloseAt()).isNull();
    }
}
