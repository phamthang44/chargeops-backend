package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.model.StationAmenityCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class StationAmenitiesRepository {

    private static final String SELECT_CODES = """
            SELECT amenity_code
            FROM station_amenities
            WHERE station_id = ?
            """;
    private static final String INSERT_CODE = """
            INSERT INTO station_amenities (station_id, amenity_code)
            VALUES (?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public StationAmenitiesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findCodesByStationId(UUID stationId) {
        List<String> codes = jdbcTemplate.query(
                SELECT_CODES,
                (resultSet, rowNum) -> resultSet.getString("amenity_code"),
                stationId
        );
        return StationAmenityCode.canonicalize(codes).stream()
                .map(StationAmenityCode::code)
                .toList();
    }

    public void replaceCodes(UUID stationId, List<StationAmenityCode> codes) {
        jdbcTemplate.update("DELETE FROM station_amenities WHERE station_id = ?", stationId);
        if (!codes.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    INSERT_CODE,
                    codes,
                    codes.size(),
                    (statement, code) -> {
                        statement.setObject(1, stationId);
                        statement.setString(2, code.code());
                    }
            );
        }
    }
}
