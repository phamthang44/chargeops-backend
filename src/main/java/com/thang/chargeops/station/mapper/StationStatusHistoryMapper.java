package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.station.response.StationStatusHistoryResponse;
import com.thang.chargeops.station.entity.StationStatusHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StationStatusHistoryMapper {

    @Mapping(target = "stationId", source = "station.id")
    @Mapping(target = "stationCode", source = "station.stationCode")
    @Mapping(target = "stationName", source = "station.name")
    @Mapping(target = "eventType", source = "stationStatusEventType")
    @Mapping(target = "performedById", source = "performedBy.id")
    @Mapping(target = "performedByName", expression = "java(resolvePerformedByName(stationStatusHistory.getPerformedBy()))")
    @Mapping(target = "performedByEmail", source = "performedBy.email")
    @Mapping(target = "performedByRole", expression = "java(resolvePerformedByRole(stationStatusHistory.getStationStatusEventType()))")
    StationStatusHistoryResponse toStationStatusHistoryResponse(StationStatusHistory stationStatusHistory);

    List<StationStatusHistoryResponse> toStationStatusHistoryResponses(
            List<StationStatusHistory> stationStatusHistories
    );

    default String resolvePerformedByName(UserProfile performedBy) {
        if (performedBy == null) {
            return null;
        }

        return performedBy.getDisplayName() == null || performedBy.getDisplayName().isBlank()
                ? performedBy.getEmail()
                : performedBy.getDisplayName();
    }

    default String resolvePerformedByRole(StationStatusEventType eventType) {
        if (eventType == null) {
            return null;
        }

        return switch (eventType) {
            case SUBMITTED, RESUBMITTED, WITHDRAWN -> "STATION_OWNER";
            case APPROVED, REJECTED, SUSPENDED, REACTIVATED -> "ADMIN";
        };
    }
}
