package com.thang.chargeops.station.staff.repository;

import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    @EntityGraph(attributePaths = {"station", "staff"})
    Optional<StationStaffAssignment> findByStaff_IdAndStatus(
            UUID staffId,
            StaffAssignmentStatus status
    );

    @EntityGraph(attributePaths = {"station", "staff"})
    Optional<StationStaffAssignment> findByIdAndStation_Id(UUID assignmentId, UUID stationId);

    @EntityGraph(attributePaths = {"staff"})
    Page<StationStaffAssignment> findAllByStation_IdAndStatus(
            UUID stationId,
            StaffAssignmentStatus status,
            Pageable pageable
    );
}
