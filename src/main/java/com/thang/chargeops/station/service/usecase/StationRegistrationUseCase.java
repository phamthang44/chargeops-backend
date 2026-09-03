package com.thang.chargeops.station.service.usecase;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.response.StationCreatedResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StationRegistrationUseCase {

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;
    private final CurrentProfileProvider currentProfileProvider;
    private final AdministrativeLocationService administrativeLocationService;
    private final StationStatusHistoryService stationStatusHistoryService;

    @Transactional
    public StationCreatedResponse create(RegisterStationRequest request) {
        UserProfile owner = currentProfileProvider.requireProfile();
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_START,
                "createStationRegistration",
                owner.getId(),
                request
        );
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ProfileErrorCode.PROFILE_NOT_ACTIVE);
        }

        AdministrativeWard ward = administrativeLocationService.requireWard(
                request.wardCode()
        );
        administrativeLocationService.requireWardBelongsToProvince(
                ward,
                request.provinceCode()
        );

        Station station = stationMapper.toStationEntity(request);
        station.setStationCode(formatStationCode(
                stationRepository.nextStationCodeSequence()
        ));
        station.setOwner(owner);
        station.setWard(ward);
        station.setStatus(StationStatus.PENDING_APPROVAL);
        station.updateOperationalStatus(StationOperationalStatus.PAUSED, null);

        Station savedStation = stationRepository.save(station);
        stationStatusHistoryService.recordTransition(
                savedStation,
                StationStatusEventType.SUBMITTED,
                null,
                owner,
                null
        );
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_SUCCESS,
                "createStationRegistration",
                owner.getId(),
                "stationId=" + savedStation.getId()
                        + ", stationCode=" + savedStation.getStationCode()
        );
        return stationMapper.toStationCreatedResponse(savedStation);
    }

    private String formatStationCode(long sequence) {
        return "ST-%04d".formatted(sequence);
    }
}
