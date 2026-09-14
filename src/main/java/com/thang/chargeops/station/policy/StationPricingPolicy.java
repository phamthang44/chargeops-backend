package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest.OperatingHourRequest;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest.TouRuleRequest;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StationPricingPolicy {

    // 1. Kiểm tra quyền sở hữu và trạng thái trạm
    void validateStationOwnershipAndStatus(Station station, UUID currentOwnerId);

    // 2. Validate booking settings (minDuration in [30, 60, 90])
    void validateBookingSettings(int minDurationMinutes);

    // 3. Validate giờ mở cửa 7 ngày (openTime < closeTime nếu enabled)
    void validateOperatingHours(boolean open24Hours, List<OperatingHourRequest> hours);

     // 4. Validate TOU rates không bị chồng chéo (Overlapping) và giá hợp lệ
    void validateTouRules(List<TouRuleRequest> touRules);

    // 5. Validate guard bổ sung cho booking
    void validateOperatingHoursNotConflictingWithActiveBookings(UUID stationId,StationOperatingSchedule newSchedule, Instant now);
}
