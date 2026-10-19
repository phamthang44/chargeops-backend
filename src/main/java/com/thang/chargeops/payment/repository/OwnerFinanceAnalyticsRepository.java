package com.thang.chargeops.payment.repository;

import com.thang.chargeops.payment.dto.response.OwnerFinanceAnalyticsResponse.Day;
import com.thang.chargeops.payment.dto.response.OwnerFinanceAnalyticsResponse.Station;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OwnerFinanceAnalyticsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String FILTER = """
            FROM v_owner_simulator_cash_events
            WHERE owner_id = :ownerId
              AND (CAST(:stationId AS uuid) IS NULL OR station_id = CAST(:stationId AS uuid))
              AND occurred_at >= :from AND occurred_at < :to AND occurred_at <= :now
            """;

    public boolean canReadStation(UUID ownerId, UUID stationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM stations WHERE id = :stationId AND owner_id = :ownerId)
                    OR EXISTS(SELECT 1 FROM v_owner_simulator_cash_events
                              WHERE owner_id = :ownerId AND station_id = :stationId)
                """, new MapSqlParameterSource("ownerId", ownerId).addValue("stationId", stationId), Boolean.class));
    }

    public List<Day> daily(UUID ownerId, UUID stationId, Instant from, Instant to, Instant now) {
        return jdbc.query("""
                SELECT (occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
                       SUM(gross_vnd) AS gross, SUM(refunded_vnd) AS refunded
                """ + FILTER + " GROUP BY day ORDER BY day", params(ownerId, stationId, from, to, now),
                (rs, row) -> {
                    long gross = rs.getBigDecimal("gross").longValueExact();
                    long refunded = rs.getBigDecimal("refunded").longValueExact();
                    return new Day(rs.getDate("day").toLocalDate(), gross, refunded, Math.subtractExact(gross, refunded));
                });
    }

    public StationResult stations(UUID ownerId, UUID stationId, Instant from, Instant to, Instant now) {
        var rows = jdbc.query("""
                SELECT station_id, station_code, station_name, SUM(gross_vnd) AS gross,
                       SUM(refunded_vnd) AS refunded, COUNT(*) OVER() AS station_count
                """ + FILTER + """
                 GROUP BY station_id, station_code, station_name
                 ORDER BY SUM(gross_vnd) - SUM(refunded_vnd) DESC, station_name, station_id LIMIT 10
                """, params(ownerId, stationId, from, to, now), (rs, row) -> {
            long gross = rs.getBigDecimal("gross").longValueExact();
            long refunded = rs.getBigDecimal("refunded").longValueExact();
            return new StationRow(new Station(rs.getObject("station_id", UUID.class), rs.getString("station_code"),
                    rs.getString("station_name"), gross, refunded, Math.subtractExact(gross, refunded)),
                    rs.getLong("station_count"));
        });
        return new StationResult(rows.stream().map(StationRow::station).toList(),
                rows.isEmpty() ? 0 : rows.getFirst().count());
    }

    private MapSqlParameterSource params(UUID ownerId, UUID stationId, Instant from, Instant to, Instant now) {
        return new MapSqlParameterSource("ownerId", ownerId).addValue("stationId", stationId)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("now", Timestamp.from(now));
    }
    private record StationRow(Station station, long count) {}
    public record StationResult(List<Station> items, long total) {}
}
