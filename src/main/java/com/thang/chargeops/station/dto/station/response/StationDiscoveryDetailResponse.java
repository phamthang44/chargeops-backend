package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationDayOfWeek;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record StationDiscoveryDetailResponse(
        UUID id,
        String stationCode,
        String name,
        String description,
        String address,
        String wardName,
        String provinceName,
        BigDecimal latitude,
        BigDecimal longitude,
        String contactPhone,
        List<StationAssetResponse> assets,
        BigDecimal currentPriceVndPerKwh,
        boolean open24Hours,
        boolean openNow,
        List<OperatingHourResponse> operatingHours,
        CancellationPolicyResponse cancellationPolicy,
        List<ChargePointResponse> chargePoints
) {
    public record ChargePointResponse(
            UUID id,
            String chargePointCode,
            String name,
            String zoneLabel,
            BigDecimal maxPowerKw,
            OperationalChargePointStatus operationalStatus,
            List<ConnectorResponse> connectors
    ) {}

    public record ConnectorResponse(
            UUID id,
            String connectorCode,
            ConnectorType connectorType,
            ChargerType chargerType,
            BigDecimal powerKw,
            RuntimeStatus runtimeStatus,
            boolean availableNow
    ) {}

    public record OperatingHourResponse(
            StationDayOfWeek day,
            LocalTime openTime,
            LocalTime closeTime,
            boolean enabled
    ) {}

    public record CancellationPolicyResponse(
            int gracePeriodMinutes,
            List<RefundRuleResponse> refundRules
    ) {}

    public record RefundRuleResponse(
            String tier,
            int refundPercent,
            Integer minMinutesBeforeStartInclusive,
            Integer maxMinutesBeforeStartExclusive,
            boolean appliesToNoShow
    ) {}

}
