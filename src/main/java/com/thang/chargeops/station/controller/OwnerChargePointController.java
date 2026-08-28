package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeOperationalStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;
import com.thang.chargeops.station.service.ChargePointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations/{stationId}/charge-points")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
/*
 * TODO(staff-access): Phase sau có thể cho Staff đang ACTIVE xem charge point, xem lịch sử trạng
 * thái và chuyển operational status của thiết bị thuộc station được gán. Riêng cập nhật cấu hình
 * charge point vẫn phải OWNER-only. Không mở toàn bộ controller này; endpoint dùng chung phải gọi
 * StationAccessService.requireOwnerOrActiveStaff() trong service nghiệp vụ.
 */
public class OwnerChargePointController {

    private final ChargePointService chargePointService;

    @GetMapping
    public ResponseEntity<ApiResult<List<ChargePointDetailResponse>>> list(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.listForCurrentOwner(stationId)
        ));
    }

    @GetMapping("/{chargePointId}/status-history")
    public ResponseEntity<ApiResult<List<ChargePointStatusEventResponse>>> getStatusHistory(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.getStatusHistoryForCurrentOwner(stationId, chargePointId)
        ));
    }

    @PatchMapping("/{chargePointId}")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> update(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody UpdateChargePointRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.updateForCurrentOwner(
                        stationId,
                        chargePointId,
                        request
                )
        ));
    }

    @PatchMapping("/{chargePointId}/operational-status")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> changeOperationalStatus(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody ChangeOperationalStatusRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.changeOperationalStatusForCurrentOwner(
                        stationId,
                        chargePointId,
                        request
                )
        ));
    }
}
