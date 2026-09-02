package com.thang.chargeops.station.repository;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StationRepository extends JpaRepository<Station, UUID>, JpaSpecificationExecutor<Station> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT station FROM Station station JOIN FETCH station.owner WHERE station.id = :stationId")
    Optional<Station> findByIdForPricingUpdate(@Param("stationId") UUID stationId);

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
                           license.expiresAt AS licenseExpiresAt,
                           (
                              SELECT COUNT(cp1.id)
                              FROM ChargePoint cp1
                              WHERE cp1.station = s
                                AND cp1.provisioningStatus =
                                    com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                                AND cp1.deletedAt IS NULL
                           ) AS actualChargePointCount,
                           (
                              SELECT COUNT(cp2.id)
                              FROM ChargePoint cp2
                              WHERE cp2.station = s
                                AND cp2.provisioningStatus =
                                    com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                                AND cp2.operationalChargePointStatus =
                                    com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                                AND cp2.deletedAt IS NULL
                           ) AS onlineChargePointCount
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
            "ward.province"
    })
    @Query("""
        SELECT DISTINCT station
        FROM Station station
        WHERE station.id = :stationId
        """)
    Optional<Station> findAdminDetailById(
            @Param("stationId") UUID stationId
    );

    Optional<Station> findByOwner_Id(UUID ownerId);


    @EntityGraph(attributePaths = {
            "ward",
            "ward.province",
            "assets"
    })
    @Query("""
    select distinct station
    from Station station
    where station.id = :stationId
    """)
    Optional<Station> findDiscoveryDetailById(
            @Param("stationId") UUID stationId
    );
}
