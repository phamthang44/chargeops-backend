package com.thang.chargeops.station.staff.repository;

import com.thang.chargeops.station.staff.entity.StaffInvitation;
import com.thang.chargeops.station.staff.entity.StaffInvitationStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, UUID> {
    Page<StaffInvitation> findByStation_IdOrderByCreatedAtDesc(UUID stationId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from StaffInvitation invitation where invitation.id = :id")
    Optional<StaffInvitation> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from StaffInvitation invitation where invitation.identityUserId = :identityUserId and invitation.status = :status")
    Optional<StaffInvitation> findByIdentityUserIdAndStatusForUpdate(String identityUserId, StaffInvitationStatus status);

    Optional<StaffInvitation> findByIdentityUserId(String identityUserId);
}
