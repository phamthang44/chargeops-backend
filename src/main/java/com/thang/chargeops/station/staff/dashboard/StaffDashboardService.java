package com.thang.chargeops.station.staff.dashboard;

import com.thang.chargeops.station.staff.dashboard.dto.*;
import java.util.UUID;

public interface StaffDashboardService {
    StaffDashboardSummaryResponse summary(UUID stationId);

    StaffDashboardPageResponse<StaffDashboardBookingItem> bookings(UUID stationId, StaffDashboardBookingView view,
                                                                     int page, int size);

    StaffDashboardPageResponse<StaffDashboardEquipmentItem> equipment(UUID stationId, int page, int size);

    StaffDashboardPageResponse<StaffDashboardTicketItem> tickets(UUID stationId, StaffDashboardTicketView view,
                                                                   int page, int size);
}
