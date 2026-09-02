package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.filter.StationDiscoverySort;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import com.thang.chargeops.station.projection.StationDiscoveryConnectorTypeProjection;
import com.thang.chargeops.station.projection.StationDiscoveryItemProjection;
import com.thang.chargeops.station.repository.StationDiscoveryQueryParameters;
import com.thang.chargeops.station.repository.StationDiscoveryQueryRepository;
import com.thang.chargeops.station.service.StationDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StationDiscoveryServiceImpl implements StationDiscoveryService {

    private static final ZoneId SYSTEM_ZONE_ID = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
    private static final String NO_CONNECTOR_TYPE_FILTER = "__NO_CONNECTOR_TYPE_FILTER__";

    private final StationDiscoveryQueryRepository stationDiscoveryQueryRepository;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public Page<StationDiscoveryItemResponse> searchStations(
            StationDiscoveryFilter filter,
            int page,
            int size
    ) {
        StationDiscoveryQueryParameters parameters = toQueryParameters(
                filter,
                Instant.now(applicationClock)
        );

        Page<StationDiscoveryItemProjection> stations =
                stationDiscoveryQueryRepository.findStations(
                        parameters,
                        PageRequest.of(page - 1, size)
                );
        log.info("stations: {}", stations.getContent().size());
        Map<UUID, Set<ConnectorType>> connectorTypesByStation =
                loadConnectorTypes(stations);

        return stations.map(station -> toResponse(
                station,
                connectorTypesByStation.getOrDefault(stationId(station), Set.of())
        ));
    }

    /**
     * VI: Chuẩn hóa HTTP filter và thời điểm hiện tại thành đúng kiểu dữ liệu mà SQL cần.
     * EN: Normalizes the HTTP filter and current instant into the values required by SQL.
     */
    private StationDiscoveryQueryParameters toQueryParameters(
            StationDiscoveryFilter filter,
            Instant now
    ) {
        ZonedDateTime localNow = now.atZone(SYSTEM_ZONE_ID);
        boolean hasCoordinates = hasCompleteCoordinates(filter);
        List<String> connectorTypes = toConnectorTypeNames(filter.getConnectorTypes());
        boolean connectorTypesEmpty = connectorTypes.isEmpty();

        return StationDiscoveryQueryParameters.builder()
                .queryPattern(toQueryPattern(filter.getQuery()))
                .provinceCode(normalizeText(filter.getProvinceCode()))
                .connectorTypesEmpty(connectorTypesEmpty)
                .connectorTypes(connectorTypesEmpty
                        ? List.of(NO_CONNECTOR_TYPE_FILTER)
                        : connectorTypes)
                .chargerType(enumName(filter.getChargerType()))
                .minPowerKw(filter.getMinPowerKw())
                .availableOnly(Boolean.TRUE.equals(filter.getAvailableOnly()))
                .openOnly(Boolean.TRUE.equals(filter.getOpenOnly()))
                .latitude(hasCoordinates ? filter.getLatitude() : null)
                .longitude(hasCoordinates ? filter.getLongitude() : null)
                .maxDistanceKm(hasCoordinates ? filter.getMaxDistanceKm() : null)
                .sort(resolveSort(filter, hasCoordinates).name())
                .at(now)
                .localTime(localNow.toLocalTime())
                .dayOfWeek(localNow.getDayOfWeek().name())
                .previousDayOfWeek(localNow.minusDays(1).getDayOfWeek().name())
                .priceDayType(toPriceDayType(localNow.getDayOfWeek()))
                .build();
    }

    /**
     * VI: Chọn kiểu sắp xếp thực tế; NEAREST lùi về AVAILABLE khi thiếu tọa độ.
     * EN: Resolves the effective sort; NEAREST falls back to AVAILABLE without coordinates.
     */
    private StationDiscoverySort resolveSort(
            StationDiscoveryFilter filter,
            boolean hasCoordinates
    ) {
        StationDiscoverySort requestedSort = filter.getSort() == null
                ? StationDiscoverySort.NEAREST
                : filter.getSort();

        if (requestedSort == StationDiscoverySort.NEAREST && !hasCoordinates) {
            return StationDiscoverySort.AVAILABLE;
        }
        return requestedSort;
    }

    /**
     * VI: Chỉ coi vị trí hợp lệ khi có đủ cả latitude và longitude.
     * EN: Treats a location as present only when both latitude and longitude exist.
     */
    private boolean hasCompleteCoordinates(StationDiscoveryFilter filter) {
        return filter.getLatitude() != null && filter.getLongitude() != null;
    }

    /**
     * VI: Tải connector types cho đúng các station của trang hiện tại và bỏ qua query khi trang rỗng.
     * EN: Loads connector types for the current page only and skips the query for an empty page.
     */
    private Map<UUID, Set<ConnectorType>> loadConnectorTypes(
            Page<StationDiscoveryItemProjection> stations
    ) {
        List<UUID> stationIds = stations.getContent().stream()
                .map(this::stationId)
                .toList();

        if (stationIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Set<ConnectorType>> groupedConnectorTypes = new HashMap<>();
        for (StationDiscoveryConnectorTypeProjection row
                : stationDiscoveryQueryRepository.findConnectorTypes(stationIds)) {
            if (row.getConnectorType() == null) {
                continue;
            }

            groupedConnectorTypes
                    .computeIfAbsent(
                            row.getStationId(),
                            ignored -> EnumSet.noneOf(ConnectorType.class)
                    )
                    .add(row.getConnectorType());
        }

        groupedConnectorTypes.replaceAll(
                (stationId, types) -> Collections.unmodifiableSet(EnumSet.copyOf(types))
        );
        return groupedConnectorTypes;
    }

    /**
     * VI: Ghép projection một dòng/station với connector types để tạo response DTO.
     * EN: Combines the one-row-per-station projection with connector types into the response DTO.
     */
    private StationDiscoveryItemResponse toResponse(
            StationDiscoveryItemProjection station,
            Set<ConnectorType> connectorTypes
    ) {
        return new StationDiscoveryItemResponse(
                stationId(station),
                station.getName(),
                station.getAddressLine(),
                station.getLatitude(),
                station.getLongitude(),
                roundDistance(station.getDistanceKm()),
                station.getPrimaryImageUrl(),
                station.getPriceFromVndPerKwh(),
                station.getMaxPowerKw(),
                connectorTypes,
                toInt(station.getTotalConnectorCount()),
                toInt(station.getAvailableConnectorCount()),
                Boolean.TRUE.equals(station.getOpenNow())
        );
    }

    /**
     * VI: Chuyển id dạng chuỗi của native projection về UUID dùng trong service.
     * EN: Converts the native projection string id into the UUID used by the service.
     */
    private UUID stationId(StationDiscoveryItemProjection station) {
        return UUID.fromString(station.getId());
    }

    /**
     * VI: Chuyển tập enum connector thành danh sách tên ổn định để bind vào mệnh đề IN.
     * EN: Converts connector enums into a stable name list for binding to the IN clause.
     */
    private List<String> toConnectorTypeNames(Set<ConnectorType> connectorTypes) {
        if (connectorTypes == null || connectorTypes.isEmpty()) {
            return List.of();
        }

        return connectorTypes.stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }

    /**
     * VI: Chuẩn hóa từ khóa và escape ký tự LIKE để %, _ và ! được tìm như ký tự thường.
     * EN: Normalizes the keyword and escapes LIKE metacharacters so %, _ and ! stay literal.
     */
    private String toQueryPattern(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }

        String escaped = normalized
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        return "%" + escaped + "%";
    }

    /**
     * VI: Cắt khoảng trắng; giá trị null hoặc chỉ có khoảng trắng được xem là không lọc.
     * EN: Trims text; null or blank values mean that the filter is absent.
     */
    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * VI: Chuyển enum tùy chọn sang tên lưu trong database mà vẫn giữ được null.
     * EN: Converts an optional enum to its database name while preserving null.
     */
    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * VI: Quy đổi ngày trong tuần sang nhóm giá WEEKDAY hoặc WEEKEND.
     * EN: Maps a day of week to the WEEKDAY or WEEKEND pricing group.
     */
    private String toPriceDayType(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case SATURDAY, SUNDAY -> "WEEKEND";
            default -> "WEEKDAY";
        };
    }

    /**
     * VI: Làm tròn khoảng cách hiển thị đến hai chữ số thập phân theo HALF_UP.
     * EN: Rounds the displayed distance to two decimals using HALF_UP.
     */
    private BigDecimal roundDistance(Double distanceKm) {
        if (distanceKm == null) {
            return null;
        }
        return BigDecimal.valueOf(distanceKm).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * VI: Đổi số đếm nullable từ projection sang int an toàn; null được xem là 0.
     * EN: Safely converts a nullable projection count to int; null becomes zero.
     */
    private int toInt(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }
}
