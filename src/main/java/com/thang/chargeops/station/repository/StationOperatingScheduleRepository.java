package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationOperatingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StationOperatingScheduleRepository extends JpaRepository<StationOperatingSchedule, UUID> {

    List<StationOperatingSchedule> findByStationIdOrderByEffectiveFromDescCreatedAtDesc(UUID stationId);

    @Query("""
        SELECT s FROM StationOperatingSchedule s
        WHERE s.station.id = :stationId
          AND s.cancelledAt IS NULL
          AND s.effectiveFrom <= :now
          AND (s.effectiveTo IS NULL OR s.effectiveTo > :now)
        ORDER BY s.effectiveFrom DESC
    """)
    Optional<StationOperatingSchedule> findActiveByStationId(
            @Param("stationId") UUID stationId,
            @Param("now") Instant now
    );

    @Query("""
        SELECT DISTINCT s FROM StationOperatingSchedule s
        LEFT JOIN FETCH s.periods
        WHERE s.station.id = :stationId
          AND s.cancelledAt IS NULL
          AND s.effectiveFrom < :rangeEnd
          AND (s.effectiveTo IS NULL OR s.effectiveTo > :rangeStart)
        ORDER BY s.effectiveFrom ASC
    """)
    List<StationOperatingSchedule> findEffectiveVersionsOverlapping(
            @Param("stationId") UUID stationId,
            @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd
    );

    @Query("""
        SELECT s FROM StationOperatingSchedule s
        WHERE s.station.id = :stationId
          AND s.cancelledAt IS NULL
          AND s.effectiveFrom > :now
        ORDER BY s.effectiveFrom ASC
    """)
    List<StationOperatingSchedule> findPendingByStationId(
            @Param("stationId") UUID stationId,
            @Param("now") Instant now
    );

    @Query("""
        SELECT DISTINCT s FROM StationOperatingSchedule s
        LEFT JOIN FETCH s.periods
        WHERE s.station.id IN :stationIds
          AND s.cancelledAt IS NULL
          AND s.effectiveFrom <= :now
          AND (s.effectiveTo IS NULL OR s.effectiveTo > :now)
    """)
    List<StationOperatingSchedule> findActiveByStationIds(
            @Param("stationIds") List<UUID> stationIds,
            @Param("now") Instant now
    );
}
