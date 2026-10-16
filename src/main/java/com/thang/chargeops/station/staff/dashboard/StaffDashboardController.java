package com.thang.chargeops.station.staff.dashboard;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.staff.dashboard.dto.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping(SystemConstant.API_URL_PATTERN + "staff/stations/{stationId}/dashboard")
@PreAuthorize("hasRole('STAFF')")
public class StaffDashboardController {
    private final StaffDashboardService dashboard;

    @GetMapping("/summary")
    public ResponseEntity<ApiResult<StaffDashboardSummaryResponse>> summary(@PathVariable UUID stationId) {
        return noStore(dashboard.summary(stationId));
    }

    @GetMapping("/bookings")
    public ResponseEntity<ApiResult<StaffDashboardPageResponse<StaffDashboardBookingItem>>> bookings(
            @PathVariable UUID stationId,
            @RequestParam(defaultValue = "UPCOMING") StaffDashboardBookingView view,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int size) {
        return noStore(dashboard.bookings(stationId, view, page, size));
    }

    @GetMapping("/equipment")
    public ResponseEntity<ApiResult<StaffDashboardPageResponse<StaffDashboardEquipmentItem>>> equipment(
            @PathVariable UUID stationId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int size) {
        return noStore(dashboard.equipment(stationId, page, size));
    }

    @GetMapping("/tickets")
    public ResponseEntity<ApiResult<StaffDashboardPageResponse<StaffDashboardTicketItem>>> tickets(
            @PathVariable UUID stationId,
            @RequestParam(defaultValue = "UNCLAIMED") StaffDashboardTicketView view,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int size) {
        return noStore(dashboard.tickets(stationId, view, page, size));
    }

    private static <T> ResponseEntity<ApiResult<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(data));
    }
}
