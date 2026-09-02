package com.thang.chargeops.station.repository;

/**
 * VI: Chứa native SQL của discovery để repository chỉ còn phần khai báo method.
 * Truy vấn dữ liệu cố ý trả đúng một dòng cho mỗi station; connector types được
 * tải riêng để phép join collection không làm sai pagination.
 *
 * <p>EN: Holds discovery native SQL so the repository only declares methods.
 * The data query deliberately returns one row per station; connector types are
 * loaded separately so collection joins cannot corrupt pagination.</p>
 */
final class StationDiscoverySql {

    private StationDiscoverySql() {
    }

    static final String SEARCH = """
            WITH equipment_stats AS (
                SELECT cp.station_id,
                       MAX(GREATEST(COALESCE(cp.max_power_kw, c.power_kw), c.power_kw)) AS max_power_kw,
                       COUNT(c.id) AS total_connector_count,
                       SUM(CASE
                               WHEN cp.operational_status = 'AVAILABLE'
                                AND c.runtime_status = 'AVAILABLE'
                               THEN 1 ELSE 0
                           END) AS available_connector_count
                FROM charge_points cp
                JOIN connectors c ON c.charge_point_id = cp.id
                WHERE cp.provisioning_status = 'ACTIVE'
                  AND cp.deleted_at IS NULL
                  AND c.deleted_at IS NULL
                GROUP BY cp.station_id
            ),
            discovery_rows AS (
                SELECT CAST(s.id AS VARCHAR) AS "id",
                       s.name AS "name",
                       s.address_line AS "addressLine",
                       s.latitude AS "latitude",
                       s.longitude AS "longitude",
                       CASE
                           WHEN CAST(:#{#parameters.latitude} AS NUMERIC) IS NULL
                             OR CAST(:#{#parameters.longitude} AS NUMERIC) IS NULL
                             OR s.latitude IS NULL
                             OR s.longitude IS NULL
                           THEN NULL
                           ELSE 6371.0 * ACOS(
                               LEAST(1.0, GREATEST(-1.0,
                                   COS(RADIANS(CAST(:#{#parameters.latitude} AS DOUBLE PRECISION)))
                                   * COS(RADIANS(CAST(s.latitude AS DOUBLE PRECISION)))
                                   * COS(RADIANS(CAST(s.longitude AS DOUBLE PRECISION))
                                       - RADIANS(CAST(:#{#parameters.longitude} AS DOUBLE PRECISION)))
                                   + SIN(RADIANS(CAST(:#{#parameters.latitude} AS DOUBLE PRECISION)))
                                   * SIN(RADIANS(CAST(s.latitude AS DOUBLE PRECISION)))
                               ))
                           )
                       END AS "distanceKm",
                       primary_asset.asset_url AS "primaryImageUrl",
                       COALESCE(
                           (
                               SELECT MIN(rate.price_per_kwh)
                               FROM tou_rates rate
                               WHERE rate.station_id = s.id
                                 AND rate.effective_from <= :#{#parameters.at}
                                 AND (rate.effective_to IS NULL OR rate.effective_to > :#{#parameters.at})
                                 AND rate.day_type IN ('DAILY', :#{#parameters.priceDayType})
                           ),
                           pricing.base_price_vnd
                       ) AS "priceFromVndPerKwh",
                       equipment.max_power_kw AS "maxPowerKw",
                       COALESCE(equipment.total_connector_count, 0) AS "totalConnectorCount",
                       COALESCE(equipment.available_connector_count, 0) AS "availableConnectorCount",
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM station_operating_schedules schedule
                           WHERE schedule.station_id = s.id
                             AND schedule.effective_from <= :#{#parameters.at}
                             AND (schedule.effective_to IS NULL OR schedule.effective_to > :#{#parameters.at})
                             AND (
                                 schedule.open_24_hours = TRUE
                                 OR EXISTS (
                                     SELECT 1
                                     FROM station_operating_periods period
                                     WHERE period.schedule_id = schedule.id
                                       AND period.is_enabled = TRUE
                                       AND period.deleted_at IS NULL
                                       AND (
                                           (
                                               period.day_of_week = :#{#parameters.dayOfWeek}
                                               AND (
                                                   (period.open_time < period.close_time
                                                       AND :#{#parameters.localTime} >= period.open_time
                                                       AND :#{#parameters.localTime} < period.close_time)
                                                   OR
                                                   (period.open_time > period.close_time
                                                       AND :#{#parameters.localTime} >= period.open_time)
                                               )
                                           )
                                           OR
                                           (
                                               period.day_of_week = :#{#parameters.previousDayOfWeek}
                                               AND period.open_time > period.close_time
                                               AND :#{#parameters.localTime} < period.close_time
                                           )
                                       )
                                 )
                             )
                       ) THEN TRUE ELSE FALSE END AS "openNow"
                FROM stations s
                JOIN wards ward ON ward.code = s.ward_code
                JOIN provinces province ON province.code = ward.province_code
                LEFT JOIN station_assets primary_asset
                       ON primary_asset.station_id = s.id
                      AND primary_asset.asset_type = 'IMAGE'
                      AND primary_asset.is_primary = TRUE
                      AND primary_asset.deleted_at IS NULL
                LEFT JOIN station_booking_settings pricing ON pricing.station_id = s.id
                LEFT JOIN equipment_stats equipment ON equipment.station_id = s.id
                WHERE s.status = 'ACTIVE'
                  AND s.deleted_at IS NULL
                  AND (
                      CAST(:#{#parameters.queryPattern} AS VARCHAR) IS NULL
                      OR LOWER(s.station_code) LIKE CAST(:#{#parameters.queryPattern} AS VARCHAR) ESCAPE '!'
                      OR LOWER(s.name) LIKE CAST(:#{#parameters.queryPattern} AS VARCHAR) ESCAPE '!'
                      OR LOWER(s.address_line) LIKE CAST(:#{#parameters.queryPattern} AS VARCHAR) ESCAPE '!'
                  )
                  AND (
                      CAST(:#{#parameters.provinceCode} AS VARCHAR) IS NULL
                      OR province.code = CAST(:#{#parameters.provinceCode} AS VARCHAR)
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM charge_points filter_cp
                      JOIN connectors filter_c ON filter_c.charge_point_id = filter_cp.id
                      WHERE filter_cp.station_id = s.id
                        AND filter_cp.provisioning_status = 'ACTIVE'
                        AND filter_cp.deleted_at IS NULL
                        AND filter_c.deleted_at IS NULL
                        AND (
                            CAST(:#{#parameters.chargerType} AS VARCHAR) IS NULL
                            OR filter_c.charger_type = CAST(:#{#parameters.chargerType} AS VARCHAR)
                        )
                        AND (
                            CAST(:#{#parameters.minPowerKw} AS NUMERIC) IS NULL
                            OR filter_c.power_kw >= CAST(:#{#parameters.minPowerKw} AS NUMERIC)
                        )
                        AND (
                            :#{#parameters.connectorTypesEmpty} = TRUE
                            OR filter_c.connector_type IN (:#{#parameters.connectorTypes})
                        )
                  )
            )
            SELECT discovery.*
            FROM discovery_rows discovery
            WHERE (:#{#parameters.availableOnly} = FALSE OR discovery."availableConnectorCount" > 0)
              AND (:#{#parameters.openOnly} = FALSE OR discovery."openNow" = TRUE)
              AND (
                  CAST(:#{#parameters.maxDistanceKm} AS NUMERIC) IS NULL
                  OR discovery."distanceKm" <= CAST(:#{#parameters.maxDistanceKm} AS DOUBLE PRECISION)
              )
            ORDER BY
                CASE WHEN :#{#parameters.sort} = 'NEAREST' THEN discovery."distanceKm" END ASC NULLS LAST,
                CASE WHEN :#{#parameters.sort} = 'CHEAPEST' THEN discovery."priceFromVndPerKwh" END ASC NULLS LAST,
                CASE WHEN :#{#parameters.sort} = 'AVAILABLE' THEN discovery."availableConnectorCount" END DESC,
                discovery."id" ASC
            """;

    static final String COUNT = """
            WITH count_rows AS (
                SELECT s.id,
                       CASE
                           WHEN CAST(:#{#parameters.latitude} AS NUMERIC) IS NULL
                             OR CAST(:#{#parameters.longitude} AS NUMERIC) IS NULL
                             OR s.latitude IS NULL
                             OR s.longitude IS NULL
                           THEN NULL
                           ELSE 6371.0 * ACOS(
                               LEAST(1.0, GREATEST(-1.0,
                                   COS(RADIANS(CAST(:#{#parameters.latitude} AS DOUBLE PRECISION)))
                                   * COS(RADIANS(CAST(s.latitude AS DOUBLE PRECISION)))
                                   * COS(RADIANS(CAST(s.longitude AS DOUBLE PRECISION))
                                       - RADIANS(CAST(:#{#parameters.longitude} AS DOUBLE PRECISION)))
                                   + SIN(RADIANS(CAST(:#{#parameters.latitude} AS DOUBLE PRECISION)))
                                   * SIN(RADIANS(CAST(s.latitude AS DOUBLE PRECISION)))
                               ))
                           )
                       END AS distance_km,
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM charge_points available_cp
                           JOIN connectors available_c ON available_c.charge_point_id = available_cp.id
                           WHERE available_cp.station_id = s.id
                             AND available_cp.provisioning_status = 'ACTIVE'
                             AND available_cp.operational_status = 'AVAILABLE'
                             AND available_cp.deleted_at IS NULL
                             AND available_c.runtime_status = 'AVAILABLE'
                             AND available_c.deleted_at IS NULL
                       ) THEN TRUE ELSE FALSE END AS available_now,
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM station_operating_schedules schedule
                           WHERE schedule.station_id = s.id
                             AND schedule.effective_from <= :#{#parameters.at}
                             AND (schedule.effective_to IS NULL OR schedule.effective_to > :#{#parameters.at})
                             AND (
                                 schedule.open_24_hours = TRUE
                                 OR EXISTS (
                                     SELECT 1
                                     FROM station_operating_periods period
                                     WHERE period.schedule_id = schedule.id
                                       AND period.is_enabled = TRUE
                                       AND period.deleted_at IS NULL
                                       AND (
                                           (
                                               period.day_of_week = :#{#parameters.dayOfWeek}
                                               AND (
                                                   (period.open_time < period.close_time
                                                       AND :#{#parameters.localTime} >= period.open_time
                                                       AND :#{#parameters.localTime} < period.close_time)
                                                   OR
                                                   (period.open_time > period.close_time
                                                       AND :#{#parameters.localTime} >= period.open_time)
                                               )
                                           )
                                           OR
                                           (
                                               period.day_of_week = :#{#parameters.previousDayOfWeek}
                                               AND period.open_time > period.close_time
                                               AND :#{#parameters.localTime} < period.close_time
                                           )
                                       )
                                 )
                             )
                       ) THEN TRUE ELSE FALSE END AS open_now
                FROM stations s
                JOIN wards ward ON ward.code = s.ward_code
                JOIN provinces province ON province.code = ward.province_code
                WHERE s.status = 'ACTIVE'
                  AND s.deleted_at IS NULL
                  AND (
                      CAST(:#{#parameters.queryPattern} AS VARCHAR) IS NULL
                      OR LOWER(s.station_code) LIKE CAST(:#{#parameters.queryPattern} AS VARCHAR) ESCAPE '!'
                      OR LOWER(s.name) LIKE CAST(:#{#parameters.queryPattern} AS VARCHAR) ESCAPE '!'
                      OR LOWER(s.address_line) LIKE CAST(:#{#parameters.queryPattern} AS VARCHAR) ESCAPE '!'
                  )
                  AND (
                      CAST(:#{#parameters.provinceCode} AS VARCHAR) IS NULL
                      OR province.code = CAST(:#{#parameters.provinceCode} AS VARCHAR)
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM charge_points filter_cp
                      JOIN connectors filter_c ON filter_c.charge_point_id = filter_cp.id
                      WHERE filter_cp.station_id = s.id
                        AND filter_cp.provisioning_status = 'ACTIVE'
                        AND filter_cp.deleted_at IS NULL
                        AND filter_c.deleted_at IS NULL
                        AND (
                            CAST(:#{#parameters.chargerType} AS VARCHAR) IS NULL
                            OR filter_c.charger_type = CAST(:#{#parameters.chargerType} AS VARCHAR)
                        )
                        AND (
                            CAST(:#{#parameters.minPowerKw} AS NUMERIC) IS NULL
                            OR filter_c.power_kw >= CAST(:#{#parameters.minPowerKw} AS NUMERIC)
                        )
                        AND (
                            :#{#parameters.connectorTypesEmpty} = TRUE
                            OR filter_c.connector_type IN (:#{#parameters.connectorTypes})
                        )
                  )
            )
            SELECT COUNT(*)
            FROM count_rows discovery
            WHERE (:#{#parameters.availableOnly} = FALSE OR discovery.available_now = TRUE)
              AND (:#{#parameters.openOnly} = FALSE OR discovery.open_now = TRUE)
              AND (
                  CAST(:#{#parameters.maxDistanceKm} AS NUMERIC) IS NULL
                  OR discovery.distance_km <= CAST(:#{#parameters.maxDistanceKm} AS DOUBLE PRECISION)
              )
            """;
}
