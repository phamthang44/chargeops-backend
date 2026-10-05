package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.dto.response.TicketKpiResponse;
import com.thang.chargeops.support.repository.TicketKpiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketKpiService {
    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;
    private final StationRepository stations;
    private final StationStaffAssignmentRepository assignments;
    private final TicketKpiRepository kpis;

    @Transactional(readOnly = true)
    public TicketKpiResponse get(UUID stationId, UUID staffId, Instant from, Instant to) {
        var actor = currentProfile.requireProfile();
        if (from == null || to == null || !from.isBefore(to)) throw new IllegalArgumentException("Invalid KPI range");
        boolean admin = access.hasRole("ADMIN");
        boolean owner = access.hasRole("OWNER") && stations.findById(stationId)
                .map(station -> station.getOwner().getId().equals(actor.getId())).orElse(false);
        boolean staff = assignments.existsByStation_IdAndStaff_IdAndStatus(
                stationId, actor.getId(), StaffAssignmentStatus.ACTIVE);
        if (!admin && !owner && !staff) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        if (staff && !owner && !admin && staffId != null && !staffId.equals(actor.getId()))
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        UUID target = staffId == null && staff && !owner && !admin ? actor.getId() : staffId;
        boolean allStaff = target == null;
        long claimed = kpis.countActorEvents(stationId, target, allStaff, "CLAIMED", from, to);
        long assigned = kpis.countAssigned(stationId, target, allStaff, from, to);
        long resolved = kpis.countActorEvents(stationId, target, allStaff, "RESOLVED", from, to);
        long confirmed = kpis.countCompleted(stationId, target, allStaff, "REPORTER_CONFIRMED", from, to);
        long autoClosed = kpis.countCompleted(stationId, target, allStaff, "AUTO_CLOSED_NO_RESPONSE", from, to);
        return new TicketKpiResponse(stationId, target, from, to, claimed, assigned, resolved,
                confirmed + autoClosed, confirmed, autoClosed);
    }
}
