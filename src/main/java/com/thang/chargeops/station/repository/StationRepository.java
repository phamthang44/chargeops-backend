package com.thang.chargeops.station.repository;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StationRepository extends JpaRepository<Station, UUID>, JpaSpecificationExecutor<Station> {

    @Query(value = "SELECT nextval('station_code_seq')", nativeQuery = true)
    long nextStationCodeSequence();

    @Query(
            value = """
                    SELECT s.id AS id,
                           s.stationCode AS stationCode,
                           s.name AS name,
                           s.addressLine AS addressLine,
                           province.fullName AS provinceName,
                           ward.fullName AS wardName,
                           s.plannedChargePointCount AS plannedChargePointCount,
                           s.status AS status,
                           license.plan AS licensePlan,
                           license.expiresAt AS licenseExpiresAt
                    FROM Station s
                    JOIN s.ward ward
                    JOIN ward.province province
                    LEFT JOIN License license
                        ON license.station = s
                        AND license.status = com.thang.chargeops.common.enums.LicenseStatus.ACTIVE
                        AND license.startAt <= :at
                        AND license.expiresAt > :at
                    WHERE s.owner.id = :ownerId
                    """,
            countQuery = """
                    SELECT COUNT(s)
                    FROM Station s
                    WHERE s.owner.id = :ownerId
                    """
    )
    Page<OwnerStationSummaryProjection> findOwnerStationSummaries(
            @Param("ownerId") UUID ownerId,
            @Param("at") Instant at,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT s.id AS id,
                           s.stationCode AS stationCode,
                           s.name AS name,
                           COALESCE(NULLIF(owner.displayName, ''), owner.email) AS ownerDisplayName,
                           province.fullName AS provinceName,
                           s.plannedChargePointCount AS plannedChargePointCount,
                           s.createdAt AS submittedAt
                    FROM Station s
                    JOIN s.owner owner
                    JOIN s.ward ward
                    JOIN ward.province province
                    WHERE s.status = :status
                    """,
            countQuery = """
                    SELECT COUNT(s)
                    FROM Station s
                    WHERE s.status = :status
                    """
    )
    Page<StationApprovalSummaryProjection> findStationApprovalSummaries(
            @Param("status") StationStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {
            "owner",
            "ward",
            "ward.province",
            "assets"
    })
    @Query("""
        SELECT DISTINCT station
        FROM Station station
        WHERE station.id = :stationId
        """)
    Optional<Station> findApprovalDetailById(
            @Param("stationId") UUID stationId
    );

    @Override
    @EntityGraph(attributePaths = {"owner", "ward", "ward.province"})
    Page<Station> findAll(Specification<Station> specification, Pageable pageable);

    @EntityGraph(attributePaths = {
            "owner",
            "ward",
            "ward.province",
            "assets",
            "operatingPeriods"
    })
    @Query("""
        SELECT DISTINCT station
        FROM Station station
        WHERE station.id = :stationId
        """)
    Optional<Station> findAdminDetailById(
            @Param("stationId") UUID stationId
    );

}
