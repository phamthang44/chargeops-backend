package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.StationAssetType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.RegisterStationAssetRequest;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.repository.StationAssetRepository;
import com.thang.chargeops.station.service.StationAssetUploadService;
import com.thang.chargeops.station.service.StationService;
import io.imagekit.client.ImageKitClient;
import io.imagekit.models.files.FileUploadParams;
import io.imagekit.models.files.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StationAssetUploadServiceImpl implements StationAssetUploadService {

    private final ImageKitClient imageKitClient;
    private final StationService stationService;
    private final StationAssetRepository stationAssetRepository;
    private final CurrentProfileProvider currentProfileProvider;

    @Override
    @Transactional
    public StationAsset uploadStationImage(UUID stationId, MultipartFile file, boolean primary) {
        Station station = stationService.getStationById(stationId);
        UserProfile owner = currentProfileProvider.requireProfile();
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }
        validateImage(file);

        List<StationAsset> existingAssets =
                stationAssetRepository.findByStationIdOrderByDisplayOrderAsc(stationId);
        boolean makePrimary = primary || existingAssets.stream()
                .noneMatch(asset -> asset.getAssetType() == StationAssetType.IMAGE
                        && asset.isPrimaryAsset());
        if (makePrimary) {
            existingAssets.stream()
                    .filter(asset -> asset.getAssetType() == StationAssetType.IMAGE)
                    .forEach(asset -> asset.setPrimaryAsset(false));
        }

        FileUploadResponse uploaded = uploadToImageKit(stationId, file);

        StationAsset asset = StationAsset.builder()
                .station(station)
                .assetType(StationAssetType.IMAGE)
                .assetUrl(requireUploadedValue(
                        uploaded.url(),
                        "ImageKit response does not contain an URL"
                ))
                .storageKey(requireUploadedValue(
                        uploaded.fileId(),
                        "ImageKit response does not contain a file ID"
                ))
                .primaryAsset(makePrimary)
                .displayOrder(existingAssets.size())
                .altText(station.getName())
                .build();

        return stationAssetRepository.save(asset);
    }

    @Override
    @Transactional
    public StationAsset registerStationAsset(UUID stationId, RegisterStationAssetRequest request) {
        Station station = stationService.getStationById(stationId);
        UserProfile owner = currentProfileProvider.requireProfile();
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }

        List<StationAsset> existingAssets =
                stationAssetRepository.findByStationIdOrderByDisplayOrderAsc(stationId);

        boolean makePrimary = request.isPrimary() || existingAssets.stream()
                .noneMatch(asset -> asset.getAssetType() == StationAssetType.IMAGE && asset.isPrimaryAsset());

        if (makePrimary) {
            existingAssets.stream()
                    .filter(asset -> asset.getAssetType() == StationAssetType.IMAGE)
                    .forEach(asset -> asset.setPrimaryAsset(false));
        }

        StationAssetType type = request.getAssetType() != null ? request.getAssetType() : StationAssetType.IMAGE;
        String altText = StringUtils.hasText(request.getAltText()) ? request.getAltText() : station.getName();

        StationAsset asset = StationAsset.builder()
                .station(station)
                .assetType(type)
                .assetUrl(request.getAssetUrl())
                .storageKey(request.getStorageKey())
                .primaryAsset(makePrimary)
                .displayOrder(existingAssets.size())
                .altText(altText)
                .build();

        return stationAssetRepository.save(asset);
    }

    @Override
    @Transactional
    public void deleteStationAsset(UUID stationId, UUID assetId) {
        Station station = stationService.getStationById(stationId);
        UserProfile owner = currentProfileProvider.requireProfile();
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }

        StationAsset asset = stationAssetRepository.findByIdAndStationId(assetId, stationId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Asset not found"));

        stationAssetRepository.delete(asset);

        if (StringUtils.hasText(asset.getStorageKey())) {
            try {
                imageKitClient.files().delete(asset.getStorageKey());
                log.info("Deleted asset file from ImageKit: fileId={}", asset.getStorageKey());
            } catch (Exception e) {
                log.warn("Failed to delete asset from ImageKit: fileId={}, error={}", asset.getStorageKey(), e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public StationAsset setPrimaryAsset(UUID stationId, UUID assetId) {
        Station station = stationService.getStationById(stationId);
        UserProfile owner = currentProfileProvider.requireProfile();
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }

        List<StationAsset> existingAssets =
                stationAssetRepository.findByStationIdOrderByDisplayOrderAsc(stationId);

        StationAsset target = existingAssets.stream()
                .filter(a -> a.getId().equals(assetId))
                .findFirst()
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Asset not found"));

        for (StationAsset a : existingAssets) {
            if (a.getAssetType() == StationAssetType.IMAGE) {
                a.setPrimaryAsset(a.getId().equals(assetId));
            }
        }

        return target;
    }

    @Override
    public List<StationAsset> getStationAssets(UUID stationId) {
        Station station = stationService.getStationById(stationId);
        UserProfile owner = currentProfileProvider.requireProfile();
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }
        return stationAssetRepository.findByStationIdOrderByDisplayOrderAsc(stationId);
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST, "Image file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST, "Only image files are allowed");
        }
    }

    private FileUploadResponse uploadToImageKit(UUID stationId, MultipartFile file) {
        try {
            FileUploadParams params = FileUploadParams.builder()
                    .file(file.getBytes())
                    .fileName(resolveFileName(file))
                    .folder("/stations/" + stationId)
                    .addTag("station")
                    .build();

            return imageKitClient.files().upload(params);
        } catch (IOException exception) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST, "Cannot read uploaded image");
        } catch (RuntimeException exception) {
            throw new AppException(CommonErrorCode.INTERNAL_ERROR, "ImageKit upload failed");
        }
    }

    private String resolveFileName(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            return UUID.randomUUID() + ".jpg";
        }
        return UUID.randomUUID() + "-" + originalName.trim().replaceAll("\\s+", "_");
    }

    private String requireUploadedValue(java.util.Optional<String> value, String message) {
        return value.orElseThrow(() -> new AppException(
                CommonErrorCode.INTERNAL_ERROR,
                message
        ));
    }
}
