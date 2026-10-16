package com.thang.chargeops.station.staff.dashboard;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StaffOperationErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.access.service.StationAccessService;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.dashboard.dto.*;
import com.thang.chargeops.station.staff.dashboard.repository.StaffDashboardReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StaffDashboardServiceImpl implements StaffDashboardService {
    private static final ZoneId ZONE = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
    private static final int MAX_PAGE_SIZE = 20;

    private final CurrentProfileProvider currentProfile;
    private final StationAccessService stationAccess;
    private final StaffDashboardReadRepository queries;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StaffDashboardSummaryResponse summary(UUID stationId) {
        requireReadableStation(stationId);
        return queries.summary(stationId, applicationClock.instant());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StaffDashboardPageResponse<StaffDashboardBookingItem> bookings(UUID stationId,
            StaffDashboardBookingView view, int requestedPage, int requestedSize) {
        requireReadableStation(stationId);
        PageRequest page = pageRequest(requestedPage, requestedSize);
        Instant now = applicationClock.instant();
        Instant dayEnd = LocalDate.ofInstant(now, ZONE).plusDays(1).atStartOfDay(ZONE).toInstant();
        long total = queries.countBookings(stationId, view, now, dayEnd);
        return new StaffDashboardPageResponse<>(stationId, now,
                queries.bookings(stationId, view, now, dayEnd, page.offset(), page.size()),
                page.number(), page.size(), total, totalPages(total, page.size()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StaffDashboardPageResponse<StaffDashboardEquipmentItem> equipment(UUID stationId,
            int requestedPage, int requestedSize) {
        requireReadableStation(stationId);
        PageRequest page = pageRequest(requestedPage, requestedSize);
        long total = queries.countEquipmentChargePoints(stationId) + queries.countEquipmentConnectors(stationId);
        return new StaffDashboardPageResponse<>(stationId, applicationClock.instant(),
                queries.equipment(stationId, page.offset(), page.size()), page.number(), page.size(), total,
                totalPages(total, page.size()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StaffDashboardPageResponse<StaffDashboardTicketItem> tickets(UUID stationId,
            StaffDashboardTicketView view, int requestedPage, int requestedSize) {
        UUID handlerId = currentProfile.requireProfileId();
        requireReadableStation(stationId);
        PageRequest page = pageRequest(requestedPage, requestedSize);
        boolean unclaimed = view == StaffDashboardTicketView.UNCLAIMED;
        long total = queries.countTickets(stationId, handlerId, unclaimed);
        return new StaffDashboardPageResponse<>(stationId, applicationClock.instant(),
                queries.tickets(stationId, handlerId, unclaimed, page.offset(), page.size()),
                page.number(), page.size(), total, totalPages(total, page.size()));
    }

    private void requireReadableStation(UUID stationId) {
        if (currentProfile.requireProfile().getStatus() != UserStatus.ACTIVE) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
        }
        Station station = stationAccess.requireActiveStaff(stationId);
        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(StaffOperationErrorCode.STATION_UNAVAILABLE);
        }
    }

    private static PageRequest pageRequest(int requestedPage, int requestedSize) {
        if (requestedPage < 1 || requestedSize < 1 || requestedSize > MAX_PAGE_SIZE) {
            throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        }
        int offset = (int) Math.min((long) (requestedPage - 1) * requestedSize, Integer.MAX_VALUE);
        return new PageRequest(requestedPage, requestedSize, offset);
    }

    private static int totalPages(long total, int size) {
        return total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
    }

    private record PageRequest(int number, int size, int offset) {}
}
