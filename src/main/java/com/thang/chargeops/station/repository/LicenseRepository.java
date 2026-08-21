package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.License;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LicenseRepository extends JpaRepository<License, UUID>, JpaSpecificationExecutor<License> {

    /**
     * Write-side mirror of PostgreSQL partial unique index:
     * <p>
     * ux_licenses_one_active_per_station
     * ON licenses(station_id)
     * WHERE status = 'ACTIVE'
     * <p>
     * Defined in V1__initial_core_schema.sql.
     * <p>
     * Use this query before commands that create an ACTIVE row
     * such as issue/reactivate. The database index remains the
     * final concurrency guard.
     */
    @Query("""
            SELECT (COUNT(l) > 0)
            FROM License l
            WHERE l.station.id = :stationId
              AND l.status = com.thang.chargeops.common.enums.LicenseStatus.ACTIVE
            """)
    boolean existsPersistedActiveLicenseForStation(
            @Param("stationId") UUID stationId
    );

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

    @Query("""
            SELECT l.id
            FROM License l
            WHERE l.status IN (
                com.thang.chargeops.common.enums.LicenseStatus.ACTIVE,
                com.thang.chargeops.common.enums.LicenseStatus.SUSPENDED
              )
              AND l.expiresAt <= :at
            ORDER BY l.expiresAt ASC
            """)
    List<UUID> findDueNonTerminalLicenseIds(
            @Param("at") Instant at,
            Pageable pageable
    );

    /**
     * Returns renewed License rows whose effective window has started but that
     * are still waiting for system activation. Restricting the query to rows
     * with renewedFrom keeps scheduled first issue outside this use case.
     */
    @Query("""
            SELECT l.id
            FROM License l
            WHERE l.status = com.thang.chargeops.common.enums.LicenseStatus.PENDING
              AND l.renewedFrom IS NOT NULL
              AND l.startAt <= :at
              AND l.expiresAt > :at
            ORDER BY l.startAt ASC
            """)
    List<UUID> findDuePendingRenewalActivationIds(
            @Param("at") Instant at,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"station", "owner"})
    Optional<License> findWithDetailsById(UUID id);

    @Override
    @EntityGraph(attributePaths = {"station", "owner"})
    Page<License> findAll(Specification<License> spec, Pageable pageable);

    @Query("""
            SELECT l FROM License l
            JOIN FETCH l.station s
            JOIN FETCH l.owner owner
            WHERE s.id = :stationId
            ORDER BY l.startAt DESC
            """)
    List<License> findStationLicenseHistory(@Param("stationId") UUID stationId);

    @Query(value = "SELECT nextval('license_code_seq')", nativeQuery = true)
    long nextLicenseCodeSequence();


    boolean existsByRenewedFrom_Id(UUID renewedFromId);


    Optional<License> findFirstByStation_IdOrderByStartAtDescCreatedAtDesc(
            UUID stationId
    );
}
