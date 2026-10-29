package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.ReplaceStationAmenitiesRequest;
import com.thang.chargeops.station.dto.station.response.StationAmenitiesResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.model.StationAmenityCode;
import com.thang.chargeops.station.repository.StationAmenitiesRepository;
import com.thang.chargeops.station.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StationAmenitiesService {

    private final CurrentProfileProvider currentProfileProvider;
    private final StationRepository stationRepository;
    private final StationAmenitiesRepository stationAmenitiesRepository;

    @Transactional(readOnly = true)
    public StationAmenitiesResponse getOwnerAmenities(UUID stationId) {
        Station station = requireOwnedStation(stationId, false);
        return response(station.getId(), stationAmenitiesRepository.findCodesByStationId(stationId));
    }

    @Transactional
    public StationAmenitiesResponse replaceOwnerAmenities(
            UUID stationId,
            ReplaceStationAmenitiesRequest request
    ) {
        if (request == null || request.amenities() == null) {
            throw new IllegalArgumentException("Amenities must be provided as an array");
        }
        Station station = requireOwnedStation(stationId, true);
        List<StationAmenityCode> canonical = StationAmenityCode.canonicalize(request.amenities());
        stationAmenitiesRepository.replaceCodes(stationId, canonical);
        return response(station.getId(), canonical.stream().map(StationAmenityCode::code).toList());
    }

    /** Called only after the Driver detail visibility policy has passed. */
    @Transactional(readOnly = true)
    public List<String> getAmenitiesForStation(UUID stationId) {
        return stationAmenitiesRepository.findCodesByStationId(stationId);
    }

    private Station requireOwnedStation(UUID stationId, boolean lockForUpdate) {
        UserProfile profile = currentProfileProvider.requireProfile();
        if (profile.getStatus() != UserStatus.ACTIVE || profile.isDeleted()) {
            throw new AppException(ProfileErrorCode.PROFILE_NOT_ACTIVE);
        }

        Station station = (lockForUpdate
                ? stationRepository.findByIdForAmenitiesUpdate(stationId)
                : stationRepository.findById(stationId))
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
        if (station.getOwner() == null || !profile.getId().equals(station.getOwner().getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }
        return station;
    }

    private StationAmenitiesResponse response(UUID stationId, List<String> amenities) {
        return new StationAmenitiesResponse(stationId, amenities);
    }
}
