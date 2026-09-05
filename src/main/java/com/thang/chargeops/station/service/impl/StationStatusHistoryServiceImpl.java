package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.response.StationStatusHistoryResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationStatusHistory;
import com.thang.chargeops.station.mapper.StationStatusHistoryMapper;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.StationStatusHistoryRepository;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StationStatusHistoryServiceImpl implements StationStatusHistoryService {

    private final StationStatusHistoryRepository stationStatusHistoryRepository;
    private final StationRepository stationRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationStatusHistoryMapper stationStatusHistoryMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransition(
            Station station,
            StationStatusEventType eventType,
            StationStatus fromStatus,
            UserProfile performedBy,
            String reason
    ) {
        StationStatus toStatus = eventType.getToStatus();

        if (!Objects.equals(eventType.getFromStatus(), fromStatus)
                || station.getStatus() != toStatus) {
            throw new AppException(
                    StationErrorCode.INVALID_STATUS_TRANSITION,
                    fromStatus,
                    toStatus
            );
        }

        StationStatusHistory history = StationStatusHistory.builder()
                .station(station)
                .stationStatusEventType(eventType)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .reason(normalizeReason(reason))
                .performedBy(performedBy)
                .performedAt(Instant.now())
                .build();

        stationStatusHistoryRepository.save(history);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationStatusHistoryResponse> getHistory(UUID stationId) {
        Station station = stationRepository.findById(stationId).orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND));
        boolean canAccess = hasAuthority("ROLE_ADMIN")
                || (hasAuthority("ROLE_OWNER")
                && station.getOwner().getId().equals(currentProfileProvider.requireProfileId()));

        if (!canAccess) {
            throw new AppException(AuthErrorCode.ACCESS_DENIED);
        }

        List<StationStatusHistory> histories = stationStatusHistoryRepository
                .findAllByStation_IdOrderByPerformedAtAscIdAsc(stationId);
        return stationStatusHistoryMapper.toStationStatusHistoryResponses(histories);
    }

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }


    private String normalizeReason(String reason) {
        return hasText(reason) ? reason.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
