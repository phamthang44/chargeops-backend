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

    List<StationOperatingSchedule> findByStationIdOrderByEffectiveFromDesc(UUID stationId);

    @Query("""
        SELECT s FROM StationOperatingSchedule s
        WHERE s.station.id = :stationId
          AND s.effectiveFrom <= :now
          AND (s.effectiveTo IS NULL OR s.effectiveTo > :now)
        ORDER BY s.effectiveFrom DESC
    """)
    Optional<StationOperatingSchedule> findActiveByStationId(
            @Param("stationId") UUID stationId,
            @Param("now") Instant now
    );
}
