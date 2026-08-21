package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.LicenseStatusActorType;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.license.response.*;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.time.ZoneId;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LicenseMapper {

    // 1. Issue License Response (sẵn có)
    @Mapping(target = "stationId", source = "station.id")
    IssueLicenseResponse toIssueLicenseResponse(License license);

    // 2. Map sang ListItemResponse (dùng cho Table List & Station History)
    @Mapping(target = "stationId", source = "license.station.id")
    @Mapping(target = "stationCode", source = "license.station.stationCode")
    @Mapping(target = "stationName", source = "license.station.name")
    @Mapping(target = "ownerName", expression = "java(resolveOwnerName(license.getOwner()))")
    @Mapping(target = "daysLeft", expression = "java(license.calculateDaysLeft())")
    @Mapping(target = "expiringSoon", expression = "java(license.isExpiringSoon())")
    AdminLicenseListItemResponse toListItemResponse(License license);

    List<AdminLicenseListItemResponse> toListItemResponseList(List<License> licenses);

    // 3. Map sang DetailResponse dùng cho Detail Drawer bên frontend
    @Mapping(target = "licenseId", source = "license.id")
    @Mapping(target = "stationId", source = "license.station.id")
    @Mapping(target = "stationCode", source = "license.station.stationCode")
    @Mapping(target = "stationName", source = "license.station.name")
    @Mapping(target = "ownerId", source = "license.owner.id")
    @Mapping(target = "ownerName", expression = "java(resolveOwnerName(license.getOwner()))")
    @Mapping(target = "ownerEmail", source = "license.owner.email")
    @Mapping(target = "daysLeft", expression = "java(license.calculateDaysLeft())")
    @Mapping(target = "isExpiringSoon", expression = "java(license.isExpiringSoon())")
    @Mapping(target = "recordedByName", source = "recordedByName")
    AdminLicenseDetailResponse toDetailResponse(License license, String recordedByName);


    // 4. Map sang License Event Status Response ( dùng cho Status Events Timeline tab 1)
    @Mapping(target = "licenseId", source = "event.license.id")
    @Mapping(target = "performedByName", expression = "java(resolvePerformerName(event.getPerformedBy(), event.getActorType()))")
    LicenseStatusEventResponse toStatusEventResponse(LicenseStatusEvent event);

    List<LicenseStatusEventResponse> toStatusEventResponseList(List<LicenseStatusEvent> events);

    @Mapping(target = "stationId", source = "station.id")
    @Mapping(target = "renewedFromLicenseId", source = "renewedFrom.id")
    RenewLicenseResponse toRenewLicenseResponse(License license);

    //  --- Presentation Formatting Helpers ---
    default String resolveOwnerName(UserProfile owner) {
        if (owner == null) return null;

        if (owner.getDisplayName() != null && !owner.getDisplayName().isBlank()) return owner.getDisplayName();

        return owner.getEmail();
    }

    default String resolvePerformerName(UserProfile performedBy, LicenseStatusActorType actorType) {
        if (actorType == LicenseStatusActorType.SYSTEM) return LicenseStatusActorType.SYSTEM.name();

        if (performedBy == null) return LicenseStatusActorType.ADMIN.name();

        if (performedBy.getDisplayName() != null && !performedBy.getDisplayName().isBlank()) return performedBy.getDisplayName();

        return performedBy.getEmail();
    }

}
