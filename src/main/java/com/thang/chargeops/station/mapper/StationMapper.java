package com.thang.chargeops.station.mapper;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.response.*;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StationMapper {

    @Mapping(target = "stationCode", ignore = true)
    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "ward", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "assets", ignore = true)
    @Mapping(target = "operatingSchedules", ignore = true)
    Station toStationEntity(RegisterStationRequest request);

    @Mapping(target = "licenseSummary", expression = "java(toLicenseSummary(projection))")
    OwnerStationSummaryResponse toOwnerStationSummaryResponse(OwnerStationSummaryProjection projection);

    @Mapping(
            target = "ownerDisplayName",
            expression = "java(resolveOwnerName(station.getOwner()))"
    )
    @Mapping(
            target = "provinceName",
            source = "station.ward.province.fullName"
    )
    @Mapping(
            target = "wardName",
            source = "station.ward.fullName"
    )
    @Mapping(
            target = "submittedAt",
            source = "station.createdAt"
    )
    @Mapping(
            target = "licenseSubmitted",
            source = "hasActiveLicense"
    )
    StationApprovalDetailResponse toStationApprovalDetailResponse(
            Station station,
            boolean hasActiveLicense
    );

    @Mapping(target = "primary", source = "primaryAsset")
    StationAssetResponse toStationAssetResponse(StationAsset asset);

    default LicenseSummaryResponse toLicenseSummary(OwnerStationSummaryProjection projection) {
        if (projection.getLicensePlan() == null || projection.getLicenseExpiresAt() == null) {
            return null;
        }

        return new LicenseSummaryResponse(
                projection.getLicensePlan(),
                projection.getLicenseExpiresAt()
        );
    }

    default String resolveOwnerName(UserProfile owner) {
        if (owner == null) {
            return null;
        }

        return owner.getDisplayName() == null || owner.getDisplayName().isBlank()
                ? owner.getEmail()
                : owner.getDisplayName();
    }

    StationApprovalSummaryResponse toStationApprovalSummaryResponse(StationApprovalSummaryProjection projection);

    @Mapping(target = "submittedAt", source = "createdAt")
    StationCreatedResponse toStationCreatedResponse(Station station);

    @Mapping(target = "provinceName", source = "station.ward.province.fullName")
    @Mapping(target = "wardName", source = "station.ward.fullName")
    @Mapping(target = "ownerId", source = "station.owner.id")
    @Mapping(target = "ownerDisplayName", expression = "java(resolveOwnerName(station.getOwner()))")
    @Mapping(target = "ownerEmail", source = "station.owner.email")
    @Mapping(target = "licenseSummary", source = "licenseSummary")
    AdminStationListItemResponse toAdminStationListItemResponse(
            Station station,
            LicenseSummaryResponse licenseSummary
    );

    @Mapping(
            target = "ownerId",
            source = "station.owner.id"
    )
    @Mapping(
            target = "ownerDisplayName",
            expression = "java(resolveOwnerName(station.getOwner()))"
    )
    @Mapping(
            target = "ownerEmail",
            source = "station.owner.email"
    )
    @Mapping(
            target = "ownerPhoneNumber",
            source = "station.owner.phone"
    )
    @Mapping(
            target = "provinceName",
            source = "station.ward.province.fullName"
    )
    @Mapping(
            target = "wardName",
            source = "station.ward.fullName"
    )
    @Mapping(
            target = "assets",
            source = "station.assets"
    )
    @Mapping(
            target = "operatingPeriods",
            source = "operatingPeriods"
    )
    @Mapping(
            target = "licenseSummary",
            source = "licenseSummary"
    )
    AdminStationDetailResponse toAdminStationDetailResponse(
            Station station,
            List<StationOperatingPeriod> operatingPeriods,
            LicenseSummaryResponse licenseSummary
    );

    StationOperatingPeriodResponse toStationOperatingPeriodResponse(StationOperatingPeriod period);

    List<StationOperatingPeriodResponse> toStationOperatingPeriodResponseList(List<StationOperatingPeriod> periods);

}
