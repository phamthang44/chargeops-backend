package com.thang.chargeops.station.staff.repository;

import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StationStaffAssignmentRepository extends JpaRepository<StationStaffAssignment, UUID> {

    boolean existsByStaff_IdAndStatus(UUID staffId, StaffAssignmentStatus status);

    boolean existsByStation_IdAndStaff_IdAndStatus(
            UUID stationId,
            UUID staffId,
            StaffAssignmentStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StationStaffAssignment a where a.station.id = :stationId and a.staff.id = :staffId and a.status = :status")
    Optional<StationStaffAssignment> findActiveForUpdate(UUID stationId, UUID staffId, StaffAssignmentStatus status);

    @EntityGraph(attributePaths = {"station", "staff"})
    Optional<StationStaffAssignment> findByStaff_IdAndStatus(
            UUID staffId,
            StaffAssignmentStatus status
    );

    @EntityGraph(attributePaths = {"station", "staff"})
    List<StationStaffAssignment> findAllByStaff_IdAndStatus(
            UUID staffId,
            StaffAssignmentStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"station", "staff"})
    Optional<StationStaffAssignment> findByIdAndStation_Id(UUID assignmentId, UUID stationId);

    @EntityGraph(attributePaths = {"staff"})
    Page<StationStaffAssignment> findAllByStation_IdAndStatus(
            UUID stationId,
            StaffAssignmentStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"station", "staff"})
    @Query("""
            SELECT a FROM StationStaffAssignment a
            WHERE a.station.owner.id = :ownerId
              AND (:stationId IS NULL OR a.station.id = :stationId)
              AND a.status = :status
            ORDER BY a.assignedAt DESC
            """)
    Page<StationStaffAssignment> findAllByOwner(
            @Param("ownerId") UUID ownerId,
            @Param("stationId") UUID stationId,
            @Param("status") StaffAssignmentStatus status,
            Pageable pageable
    );
}
