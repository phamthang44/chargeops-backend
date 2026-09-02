package com.thang.chargeops.station.repository;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * VI: Nhóm toàn bộ điều kiện đã chuẩn hóa mà native query discovery cần.
 * Service chuyển HTTP filter sang object này đúng một lần, nhờ đó repository
 * không phải khai báo một danh sách dài các tham số rời rạc.
 *
 * <p>Nên tái sử dụng mẫu parameter object này cho các truy vấn đọc phức tạp của
 * module booking sau này, thay vì truyền nhiều tham số positional.</p>
 *
 * <p>EN: Groups all normalized conditions required by the discovery native query.
 * The service converts the HTTP filter once, so the repository avoids a long list
 * of separate arguments. Reuse this parameter-object pattern for future complex
 * booking read queries instead of passing many positional parameters.</p>
 */
@Builder
public record StationDiscoveryQueryParameters(
        String queryPattern,
        String provinceCode,
        boolean connectorTypesEmpty,
        List<String> connectorTypes,
        String chargerType,
        BigDecimal minPowerKw,
        boolean availableOnly,
        boolean openOnly,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal maxDistanceKm,
        String sort,
        Instant at,
        LocalTime localTime,
        String dayOfWeek,
        String previousDayOfWeek,
        String priceDayType
) {
}
