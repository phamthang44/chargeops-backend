package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.License;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface LicenseRepository extends JpaRepository<License, UUID> {

    @Query("""
            SELECT (COUNT(l) > 0)
            FROM License l
            WHERE l.station.id = :stationId
              AND l.status = com.thang.chargeops.common.enums.LicenseStatus.ACTIVE
              AND l.startAt <= :at
              AND l.expiresAt > :at
            """)
    boolean existsActiveLicenseForStation(
            @Param("stationId") UUID stationId,
            @Param("at") Instant at
    );
}
