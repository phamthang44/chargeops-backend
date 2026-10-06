package com.thang.chargeops.support;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.repository.*;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.TicketEscalationService;
import com.thang.chargeops.support.service.support.TicketResponseMapper;
import com.thang.chargeops.support.service.support.TicketResponseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class StaffTicketFinancialProjectionTest {
    @Test
    void staffTicketListOmitsRefundIdsWithoutChangingOwnerProjection() {
        UUID bookingId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(bookingId);
        UserProfile reporter = UserProfile.builder().email("driver@example.test").build();
        reporter.setId(UUID.randomUUID());
        SupportTicket ticket = SupportTicket.open("TKT-20261005-0001", TicketCategory.PAYMENT,
                TicketPriority.MEDIUM, reporter, null, booking,
                new SupportTicket.TicketDetails("Payment issue", "Need a review"));
        ticket.setId(UUID.randomUUID());
        ticket.prePersistAudit();
        TicketMessageRepository messages = mock(TicketMessageRepository.class);
        TicketFindingRepository findings = mock(TicketFindingRepository.class);
        RefundRepository refunds = mock(RefundRepository.class);
        TicketEventRepository events = mock(TicketEventRepository.class);
        when(messages.findByTicket_IdInOrderByCreatedAtAscIdAsc(anyList())).thenReturn(List.of());
        when(findings.findByTicket_IdInOrderByRecordedAtAscIdAsc(anyList())).thenReturn(List.of());
        when(events.findWorkflowFacts(anyList(), anyList())).thenReturn(List.of());
        Refund refund = mock(Refund.class);
        when(refund.getBooking()).thenReturn(booking);
        when(refund.getId()).thenReturn(refundId);
        when(refunds.findByBookingIdInOrderByCreatedAtAscIdAsc(anyList())).thenReturn(List.of(refund));
        TicketAccessPolicy access = mock(TicketAccessPolicy.class);
        TicketResponseService service = new TicketResponseService(messages, findings, refunds,
                mock(SupportTicketRepository.class), events, new TicketResponseMapper(),
                mock(TicketEscalationService.class),
                mock(com.thang.chargeops.booking.service.ServiceFailureDecisionReader.class),
                mock(com.thang.chargeops.profile.support.CurrentProfileProvider.class), access,
                mock(com.thang.chargeops.support.service.AdminFinanceAccessAudit.class));

        when(access.hasRole("STAFF")).thenReturn(true);
        assertThat(service.toResponse(ticket).refundIds()).isEmpty();
        verify(refunds, never()).findByBookingIdInOrderByCreatedAtAscIdAsc(anyList());

        when(access.hasRole("STAFF")).thenReturn(false);
        assertThat(service.toResponse(ticket).refundIds()).containsExactly(refundId);
    }
}
