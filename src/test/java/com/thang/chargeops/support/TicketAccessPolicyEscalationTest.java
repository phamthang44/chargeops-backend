package com.thang.chargeops.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TicketAccessPolicyEscalationTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminReadsOnlyEscalatedStationCase() {
        var escalations = mock(TicketEscalationRepository.class);
        var policy = new TicketAccessPolicy(mock(StationStaffAssignmentRepository.class), escalations);
        var admin = UserProfile.builder().email("admin@example.test").build();
        admin.setId(UUID.randomUUID());
        var driver = UserProfile.builder().email("driver@example.test").build();
        driver.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        UUID id = UUID.randomUUID();
        when(ticket.getId()).thenReturn(id);
        when(ticket.getStation()).thenReturn(mock(Station.class));
        when(ticket.getReporter()).thenReturn(driver);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(policy.canRead(ticket, admin)).isFalse();
        when(escalations.existsByTicket_Id(id)).thenReturn(true);
        assertThat(policy.canRead(ticket, admin)).isTrue();
    }
}
