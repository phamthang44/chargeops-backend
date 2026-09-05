package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.RegisterStationAssetRequest;
import com.thang.chargeops.station.dto.station.response.StationAssetResponse;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.service.StationAssetUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations/{stationId}/assets")
@PreAuthorize("hasRole('OWNER')")
public class OwnerStationAssetController {

    private final StationAssetUploadService stationAssetUploadService;
    private final StationMapper stationMapper;

    @GetMapping
    public ResponseEntity<ApiResult<List<StationAssetResponse>>> getStationAssets(
            @PathVariable UUID stationId
    ) {
        List<StationAsset> assets = stationAssetUploadService.getStationAssets(stationId);
        List<StationAssetResponse> responses = assets.stream()
                .map(stationMapper::toStationAssetResponse)
                .toList();
        return ResponseEntity.ok(ApiResult.success(responses));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<StationAssetResponse>> uploadStationImage(
            @PathVariable UUID stationId,
            @RequestParam("image") MultipartFile image,
            @RequestParam(defaultValue = "false") boolean primary
    ) {
        StationAsset asset = stationAssetUploadService.uploadStationImage(
                stationId,
                image,
                primary
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(stationMapper.toStationAssetResponse(asset)));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResult<StationAssetResponse>> registerStationAsset(
            @PathVariable UUID stationId,
            @Valid @RequestBody RegisterStationAssetRequest request
    ) {
        StationAsset asset = stationAssetUploadService.registerStationAsset(stationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(stationMapper.toStationAssetResponse(asset)));
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<ApiResult<Void>> deleteStationAsset(
            @PathVariable UUID stationId,
            @PathVariable UUID assetId
    ) {
        stationAssetUploadService.deleteStationAsset(stationId, assetId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @PatchMapping("/{assetId}/primary")
    public ResponseEntity<ApiResult<StationAssetResponse>> setPrimaryAsset(
            @PathVariable UUID stationId,
            @PathVariable UUID assetId
    ) {
        StationAsset asset = stationAssetUploadService.setPrimaryAsset(stationId, assetId);
        return ResponseEntity.ok(ApiResult.success(stationMapper.toStationAssetResponse(asset)));
    }
}
