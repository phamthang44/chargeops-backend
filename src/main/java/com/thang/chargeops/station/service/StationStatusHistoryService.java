package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.station.response.StationStatusHistoryResponse;
import com.thang.chargeops.station.entity.Station;

import java.util.List;
import java.util.UUID;

public interface StationStatusHistoryService {

    void recordTransition(
            Station station,
            StationStatusEventType eventType,
            StationStatus fromStatus,
            UserProfile performedBy,
            String reason
    );

    List<StationStatusHistoryResponse> getHistory(UUID stationId);

}
