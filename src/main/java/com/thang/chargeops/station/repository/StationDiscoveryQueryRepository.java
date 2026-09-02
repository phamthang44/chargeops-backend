package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.projection.StationDiscoveryConnectorTypeProjection;
import com.thang.chargeops.station.projection.StationDiscoveryItemProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * VI: Repository chỉ đọc dành riêng cho màn hình khám phá trạm công khai.
 * Các truy vấn đọc phức tạp của module booking sau này có thể dùng cùng mẫu:
 * gom điều kiện vào một parameter object và giữ repository method ngắn gọn.
 *
 * <p>EN: Read-only repository dedicated to public station discovery. Future
 * complex booking read queries can follow the same pattern: group conditions
 * in one parameter object and keep the repository method signature compact.</p>
 */
public interface StationDiscoveryQueryRepository extends Repository<Station, UUID> {

    /**
     * VI: Chạy truy vấn discovery với một object chứa toàn bộ điều kiện đã chuẩn hóa.
     * Spring Data đọc từng thuộc tính qua {@code #parameters}, vì vậy không cần
     * duy trì một method nội bộ có 17 tham số rời rạc.
     *
     * <p>EN: Runs the discovery query with one object containing all normalized
     * conditions. Spring Data reads its properties through {@code #parameters},
     * avoiding an internal method with 17 separate arguments.</p>
     */
    @Query(
            value = StationDiscoverySql.SEARCH,
            countQuery = StationDiscoverySql.COUNT,
            nativeQuery = true
    )
    Page<StationDiscoveryItemProjection> findStations(
            @Param("parameters") StationDiscoveryQueryParameters parameters,
            Pageable pageable
    );

    /**
     * VI: Lấy loại connector bằng truy vấn thứ hai sau khi đã phân trang station,
     * nhờ đó mỗi station chỉ chiếm một dòng trong truy vấn chính.
     *
     * <p>EN: Loads connector types in a second query after station pagination so
     * each station occupies exactly one row in the main query.</p>
     */
    @Query("""
        SELECT DISTINCT
               chargePoint.station.id AS stationId,
               connector.connectorType AS connectorType
        FROM Connector connector
        JOIN connector.chargePoint chargePoint
        WHERE chargePoint.station.id IN :stationIds
          AND chargePoint.provisioningStatus =
              com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
        """)
    List<StationDiscoveryConnectorTypeProjection> findConnectorTypes(
            @Param("stationIds") Collection<UUID> stationIds
    );
}