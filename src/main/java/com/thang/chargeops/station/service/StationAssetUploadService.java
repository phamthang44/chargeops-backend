package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.request.RegisterStationAssetRequest;
import com.thang.chargeops.station.entity.StationAsset;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface StationAssetUploadService {
    StationAsset uploadStationImage(UUID stationId, MultipartFile image, boolean primary);

    StationAsset registerStationAsset(UUID stationId, RegisterStationAssetRequest request);

    void deleteStationAsset(UUID stationId, UUID assetId);

    StationAsset setPrimaryAsset(UUID stationId, UUID assetId);

    List<StationAsset> getStationAssets(UUID stationId);
}
