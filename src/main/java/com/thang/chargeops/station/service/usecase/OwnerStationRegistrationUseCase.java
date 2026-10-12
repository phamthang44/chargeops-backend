package com.thang.chargeops.station.service.usecase;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.location.i18n.LocationNames;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.response.OwnerStationRegistrationResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OwnerStationRegistrationUseCase {
    private final StationRepository stationRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final AdministrativeLocationService locationService;
    private final StationStatusHistoryService historyService;

    @Transactional(readOnly = true)
    public OwnerStationRegistrationResponse get(UUID stationId) {
        UserProfile owner = requireActiveOwner();
        Station station = stationRepository.findAdminDetailById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
        requireOwner(station, owner);
        return response(station);
    }

    @Transactional
    public OwnerStationRegistrationResponse update(UUID stationId, long version, RegisterStationRequest request) {
        UserProfile owner = requireActiveOwner();
        Station station = requirePending(stationId, owner, version);
        var ward = locationService.requireWard(request.wardCode());
        locationService.requireWardBelongsToProvince(ward, request.provinceCode());
        station.setName(request.name().trim());
        station.setAddressLine(request.addressLine().trim());
        station.setDescription(request.description() == null ? null : request.description().trim());
        station.setWard(ward);
        station.setLatitude(request.latitude());
        station.setLongitude(request.longitude());
        station.setContactPhone(request.contactPhone().trim());
        station.setPlannedChargePointCount(request.plannedChargePointCount());
        // Flush before responding so the client receives the new optimistic version.
        stationRepository.saveAndFlush(station);
        return response(station);
    }

    @Transactional
    public void withdraw(UUID stationId, long version) {
        UserProfile owner = requireActiveOwner();
        Station station = requirePending(stationId, owner, version);
        station.setStatus(StationStatus.WITHDRAWN);
        historyService.recordTransition(station, StationStatusEventType.WITHDRAWN,
                StationStatus.PENDING_APPROVAL, owner, null);
    }

    private Station requirePending(UUID id, UserProfile owner, long version) {
        Station station = stationRepository.findByIdForOperationalStatusUpdate(id)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, id));
        requireOwner(station, owner);
        if (station.getStatus() != StationStatus.PENDING_APPROVAL || station.getVersion() != version) {
            throw new AppException(CommonErrorCode.RESOURCE_CONFLICT);
        }
        return station;
    }

    private UserProfile requireActiveOwner() {
        UserProfile owner = currentProfileProvider.requireProfile();
        if (owner.getStatus() != UserStatus.ACTIVE) throw new AppException(ProfileErrorCode.PROFILE_NOT_ACTIVE);
        return owner;
    }

    private void requireOwner(Station station, UserProfile owner) {
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, station.getId());
        }
    }

    private OwnerStationRegistrationResponse response(Station station) {
        var ward = station.getWard();
        return new OwnerStationRegistrationResponse(station.getId(), station.getStationCode(), station.getName(),
                station.getAddressLine(), station.getDescription(), ward.getProvinceCode(), ward.getCode(),
                LocationNames.provinceFullName(ward.getProvince()), LocationNames.wardFullName(ward),
                station.getLatitude(), station.getLongitude(),
                station.getContactPhone(), station.getPlannedChargePointCount(), station.getStatus(), station.getVersion());
    }
}
