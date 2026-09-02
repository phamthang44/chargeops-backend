package com.thang.chargeops.station.mapper;

import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.dto.station.response.StationAssetResponse;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StationDetailMapper {

    private final StationMapper stationMapper;

    /**
     * Chỉ ghép dữ liệu đã được service chuẩn bị thành response cuối cùng.
     * Mapper không gọi repository, policy, Clock hoặc tự tính pricing.
     */
    public StationDiscoveryDetailResponse toResponse(
            Station station,
            StationOperatingSchedule schedule,
            List<ChargePoint> chargePoints,
            BigDecimal currentPrice,
            boolean openNow,
            CancellationPolicySummary cancellationPolicy
    ) {
        return new StationDiscoveryDetailResponse(
                station.getId(),
                station.getStationCode(),
                station.getName(),
                station.getDescription(),
                station.getAddressLine(),
                station.getWard().getFullName(),
                station.getWard().getProvince().getFullName(),
                station.getLatitude(),
                station.getLongitude(),
                station.getContactPhone(),
                toAssets(station.getAssets()),
                currentPrice,
                schedule != null && schedule.isOpen24Hours(),
                openNow,
                toOperatingHours(schedule),
                toCancellationPolicy(cancellationPolicy),
                toChargePoints(chargePoints)
        );
    }

    private StationDiscoveryDetailResponse.CancellationPolicyResponse toCancellationPolicy(
            CancellationPolicySummary policy
    ) {
        return new StationDiscoveryDetailResponse.CancellationPolicyResponse(
                policy.gracePeriodMinutes(),
                policy.refundRules().stream()
                        .map(rule -> new StationDiscoveryDetailResponse.RefundRuleResponse(
                                rule.tier().name(),
                                rule.refundPercent(),
                                rule.minMinutesBeforeStartInclusive(),
                                rule.maxMinutesBeforeStartExclusive(),
                                rule.appliesToNoShow()
                        ))
                        .toList()
        );
    }

    /**
     * Map ảnh của station theo đúng display order đã được EntityGraph load sẵn.
     */
    private List<StationAssetResponse> toAssets(List<StationAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }

        return assets.stream()
                .map(stationMapper::toStationAssetResponse)
                .toList();
    }

    /**
     * Map operating schedule thành đủ bảy ngày để frontend render ổn định.
     * Việc xác định openNow không thuộc method này.
     */
    private List<StationDiscoveryDetailResponse.OperatingHourResponse> toOperatingHours(
            StationOperatingSchedule schedule
    ) {
        if (schedule == null) {
            return Arrays.stream(StationDayOfWeek.values())
                    .map(day -> new StationDiscoveryDetailResponse.OperatingHourResponse(
                            day,
                            null,
                            null,
                            false
                    ))
                    .toList();
        }

        if (schedule.isOpen24Hours()) {
            return Arrays.stream(StationDayOfWeek.values())
                    .map(day -> new StationDiscoveryDetailResponse.OperatingHourResponse(
                            day,
                            null,
                            null,
                            true
                    ))
                    .toList();
        }

        Map<StationDayOfWeek, StationOperatingPeriod> periodsByDay =
                new EnumMap<>(StationDayOfWeek.class);
        if (schedule.getPeriods() != null) {
            schedule.getPeriods().forEach(period ->
                    periodsByDay.put(period.getDayOfWeek(), period)
            );
        }

        return Arrays.stream(StationDayOfWeek.values())
                .map(day -> toOperatingHour(day, periodsByDay.get(day)))
                .toList();
    }

    private StationDiscoveryDetailResponse.OperatingHourResponse toOperatingHour(
            StationDayOfWeek day,
            StationOperatingPeriod period
    ) {
        boolean enabled = period != null && period.isEnabled();
        return new StationDiscoveryDetailResponse.OperatingHourResponse(
                day,
                enabled ? period.getOpenTime() : null,
                enabled ? period.getCloseTime() : null,
                enabled
        );
    }

    /**
     * Map danh sách charge point; connectors đã được repository fetch sẵn.
     */
    private List<StationDiscoveryDetailResponse.ChargePointResponse> toChargePoints(
            List<ChargePoint> chargePoints
    ) {
        if (chargePoints == null || chargePoints.isEmpty()) {
            return List.of();
        }

        return chargePoints.stream()
                .map(chargePoint -> new StationDiscoveryDetailResponse.ChargePointResponse(
                        chargePoint.getId(),
                        chargePoint.getChargePointCode(),
                        chargePoint.getName(),
                        chargePoint.getZoneLabel(),
                        chargePoint.getMaxPowerKw(),
                        chargePoint.getOperationalChargePointStatus(),
                        toConnectors(chargePoint)
                ))
                .toList();
    }

    /**
     * Map connector và chỉ suy ra availableNow từ trạng thái thiết bị hiện tại.
     * Busy ranges theo booking thuộc StationAvailabilityService, không thuộc mapper này.
     */
    private List<StationDiscoveryDetailResponse.ConnectorResponse> toConnectors(
            ChargePoint chargePoint
    ) {
        List<Connector> connectors = chargePoint.getConnectors();
        if (connectors == null || connectors.isEmpty()) {
            return List.of();
        }

        boolean chargePointAvailable =
                chargePoint.getProvisioningStatus() == ProvisioningStatus.ACTIVE
                        && chargePoint.getOperationalChargePointStatus()
                        == OperationalChargePointStatus.AVAILABLE;

        return connectors.stream()
                .map(connector -> new StationDiscoveryDetailResponse.ConnectorResponse(
                        connector.getId(),
                        connector.getConnectorCode(),
                        connector.getConnectorType(),
                        connector.getChargerType(),
                        connector.getPowerKw(),
                        connector.getRuntimeStatus(),
                        chargePointAvailable
                                && connector.getRuntimeStatus() == RuntimeStatus.AVAILABLE
                ))
                .toList();
    }
}
