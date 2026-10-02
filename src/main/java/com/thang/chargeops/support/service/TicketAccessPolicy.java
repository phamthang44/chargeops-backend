package com.thang.chargeops.support.service;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TicketAccessPolicy {
    private final StationStaffAssignmentRepository staffAssignments;
    private final TicketEscalationRepository escalations;

    public boolean hasRole(String role) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    public boolean isOwner(SupportTicket ticket, UUID actorId) {
        return hasRole("OWNER") && ticket.getStation() != null
                && ticket.getStation().getOwner().getId().equals(actorId);
    }

    public boolean isStaff(SupportTicket ticket, UUID actorId) {
        return ticket.getStation() != null && staffAssignments.existsByStation_IdAndStaff_IdAndStatus(
                ticket.getStation().getId(), actorId, StaffAssignmentStatus.ACTIVE);
    }

    public boolean canRead(SupportTicket ticket, UserProfile actor) {
        if (hasRole("ADMIN")) {
            return ticket.getStation() == null || escalations.existsByTicket_Id(ticket.getId());
        }
        return ticket.getReporter().getId().equals(actor.getId())
                || isOwner(ticket, actor.getId()) || isStaff(ticket, actor.getId());
    }

    public boolean canHandle(SupportTicket ticket, UUID actorId) {
        return ticket.getStation() == null ? hasRole("ADMIN")
                : isOwner(ticket, actorId) || isStaff(ticket, actorId);
    }
}
