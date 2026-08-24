package com.thang.chargeops.station.repository;

import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.station.entity.TouRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TouRateRepository extends JpaRepository<TouRate, UUID> {

    List<TouRate> findByStationIdOrderByDayTypeAscStartTimeAsc(UUID stationId);

    @Query("""
        SELECT t FROM TouRate t
        WHERE t.station.id = :stationId
          AND t.dayType = :dayType
          AND t.effectiveFrom <= :at
          AND (t.effectiveTo IS NULL OR t.effectiveTo > :at)
        ORDER BY t.startTime ASC
    """)
    List<TouRate> findActiveByStationIdAndDayType(
            @Param("stationId") UUID stationId,
            @Param("dayType") TouRateDayType dayType,
            @Param("at") Instant at
    );
}
