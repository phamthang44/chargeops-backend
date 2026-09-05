package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeOperationalStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeRuntimeStatusRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "staff/stations")
@PreAuthorize("isAuthenticated()")
public class StaffStationOperationController {

    /*
     * TODO(staff-operations): Chỉ trả dữ liệu vận hành tối thiểu của station được gán.
     * Bắt buộc gọi StationAccessService.requireActiveStaff(stationId); không trả pricing,
     * revenue, license, ownership hoặc thông tin quản trị chỉ dành cho Owner.
     */
    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResult<?>> getStationOperationalOverview(
            @PathVariable UUID stationId
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Bổ sung paging và DTO che dữ liệu cá nhân không cần thiết.
     * Ngày mặc định phải tính theo SystemConstant.SYSTEM_REGION_TIMEZONE. Chỉ cho phép xem
     * booking thuộc station đang có StaffAssignment ACTIVE.
     */
    @GetMapping("/{stationId}/bookings")
    public ResponseEntity<ApiResult<?>> listStationBookings(
            @PathVariable UUID stationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Trả danh sách ChargePoint và trạng thái hiệu lực, nhưng không
     * cho Staff sửa provisioning, cấu hình phần cứng, tên, công suất hoặc connector topology.
     */
    @GetMapping("/{stationId}/charge-points")
    public ResponseEntity<ApiResult<?>> listChargePoints(
            @PathVariable UUID stationId
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Kiểm tra ChargePoint thuộc station được gán trước khi đọc lịch sử.
     * Lịch sử phải thể hiện actor STAFF/OWNER/ADMIN/SYSTEM và thời điểm thao tác.
     */
    @GetMapping("/{stationId}/charge-points/{chargePointId}/status-history")
    public ResponseEntity<ApiResult<?>> getChargePointStatusHistory(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Tạo StaffChargePointOperationPolicy và chỉ cho phép transition
     * vận hành đã duyệt (AVAILABLE/OFFLINE/MAINTENANCE). Cấm thay provisioning status; yêu cầu
     * reason khi ngừng phục vụ; chặn khi có booking/session đang giữ; ghi audit actor STAFF và
     * thông báo Owner. Khôi phục AVAILABLE phải qua health/checklist phù hợp.
     */
    @PatchMapping("/{stationId}/charge-points/{chargePointId}/operational-status")
    public ResponseEntity<ApiResult<?>> changeChargePointOperationalStatus(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody ChangeOperationalStatusRequest request
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Chỉ trả Connector thuộc đúng ChargePoint và station được gán.
     * Response cần phân biệt trạng thái runtime do System quản lý với khả năng khai thác thực tế.
     */
    @GetMapping("/{stationId}/charge-points/{chargePointId}/connectors")
    public ResponseEntity<ApiResult<?>> listConnectors(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Kiểm tra đầy đủ station -> ChargePoint -> Connector trước khi đọc.
     * Không để Staff xem lịch sử thiết bị ngoài station đang được phân công.
     */
    @GetMapping(
            "/{stationId}/charge-points/{chargePointId}"
                    + "/connectors/{connectorId}/status-history"
    )
    public ResponseEntity<ApiResult<?>> getConnectorStatusHistory(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-operations): Staff chỉ được thao tác AVAILABLE <-> OFFLINE theo policy.
     * Cấm mọi transition đi vào hoặc đi ra IN_USE vì trạng thái đó thuộc Booking/Session/System.
     * OFFLINE yêu cầu reason và không được làm mất booking/session đang hoạt động; việc restore
     * cần OCPP health check hoặc checklist xác nhận. Ghi audit actor STAFF và thông báo Owner.
     */
    @PatchMapping(
            "/{stationId}/charge-points/{chargePointId}"
                    + "/connectors/{connectorId}/runtime-status"
    )
    public ResponseEntity<ApiResult<?>> changeConnectorRuntimeStatus(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ChangeRuntimeStatusRequest request
    ) {
        return notImplemented();
    }

    /*
     * TODO(staff-emergency): Chỉ triển khai sau khi có Session/OCPP, Incident, notification và
     * payment/refund orchestration. Emergency stop phải dừng session và ghi incident atomically;
     * tuyệt đối không chỉ ép runtimeStatus từ IN_USE sang OFFLINE.
     */
    @PostMapping(
            "/{stationId}/charge-points/{chargePointId}"
                    + "/connectors/{connectorId}/emergency-stops"
    )
    public ResponseEntity<ApiResult<?>> createEmergencyStop(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId
    ) {
        return notImplemented();
    }

    private ResponseEntity<ApiResult<?>> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

}
