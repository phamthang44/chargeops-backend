package com.thang.chargeops.profile.service;

import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.UserErrorCode;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.infra.websocket.WebSocketSessionRegistry;
import com.thang.chargeops.profile.dto.AdminUserStatusRequest;
import com.thang.chargeops.profile.dto.AdminUserStatusResponse;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.model.PrimaryRole;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * UM-02: suspend/resume DRIVERS và STAFF (UM-00 scope V1).
 *
 * <p>Một transaction DB: FOR UPDATE profile → guards → version check → blockers recheck
 * → đổi status + version bump (@Version) + status_reason/changed_at/changed_by → commit.
 * Không gọi Keycloak; suspended user vẫn login được nhưng mọi resource trả AUTH_006
 * (gate {@code CurrentActorFilter}), token cũ sống tối đa TTL rồi hết hạn.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class AdminUserStatusService {

    private static final Duration CONFIRMED_BLOCKER_LEAD = Duration.ofMinutes(15);

    private final EntityManager entityManager;
    private final UserProfileRepository userProfileRepository;
    private final CurrentProfileProvider currentProfile;
    private final IdentityRoleService identityRoleService;
    private final WebSocketSessionRegistry sessionRegistry;

    @Transactional
    public AdminUserStatusResponse updateStatus(UUID targetId, AdminUserStatusRequest request) {
        UserProfile actor = requireActiveAdminProfile();

        UserProfile target = userProfileRepository.findByIdWithLock(targetId)
                .filter(profile -> profile.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));

        // G-1 / G-2 / G-5: ADMIN, self và OWNER không mutate trong app.
        PrimaryRole role = target.getPrimaryRole();
        if (role == PrimaryRole.ADMIN || target.getId().equals(actor.getId())) {
            throw new AppException(UserErrorCode.TARGET_PROTECTED);
        }
        if (role == PrimaryRole.OWNER) {
            throw new AppException(UserErrorCode.OWNER_MANAGED_OFFLINE);
        }

        // G-3: revalidate role authoritative — không bao giờ mutate theo projection cũ.
        revalidateAuthoritativeRole(target, role);

        // Same-state: no-op idempotent — không sinh transition mới.
        if (target.getStatus() == request.status()) {
            return snapshot(target, request.commandId(), false);
        }

        if (!Objects.equals(target.getVersion(), request.expectedVersion())) {
            throw new AppException(UserErrorCode.VERSION_CONFLICT);
        }

        if (request.status() == UserStatus.SUSPENDED) {
            List<Map<String, Object>> blockers = collectBlockers(target, role);
            if (!blockers.isEmpty()) {
                throw AppException.withDetails(
                        UserErrorCode.SUSPEND_BLOCKED,
                        Map.of("blockers", blockers, "targetVersion", request.expectedVersion()));
            }
        }

        target.setStatus(request.status());
        target.setStatusReason(request.reason().trim());
        target.setStatusChangedAt(Instant.now());
        target.setStatusChangedBy(actor.getId());
        entityManager.flush(); // @Version bump trong cùng tx

        if (request.status() == UserStatus.SUSPENDED) {
            closeSessionsBestEffort(target);
        }
        log.info(
                "User status changed: target={} {} -> {} by commandId={}",
                target.getId(), role, request.status(), request.commandId()
        );
        return snapshot(target, request.commandId(), true);
    }

    private UserProfile requireActiveAdminProfile() {
        UserProfile actor = currentProfile.requireProfile();
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ProfileErrorCode.PROFILE_NOT_ACTIVE);
        }
        return actor;
    }

    private void revalidateAuthoritativeRole(UserProfile target, PrimaryRole role) {
        if (role == PrimaryRole.UNKNOWN || role == PrimaryRole.CONFLICT) {
            throw new AppException(UserErrorCode.ROLE_UNVERIFIED);
        }
        Set<Role> authoritative;
        try {
            authoritative = identityRoleService.getRoles(target.getKeycloakId());
        } catch (Exception ex) {
            log.warn("Role revalidation failed for target={}", target.getId(), ex);
            throw new AppException(UserErrorCode.ROLE_UNVERIFIED);
        }
        if (authoritative.isEmpty() || !authoritative.contains(Role.valueOf(role.name()))) {
            log.warn(
                    "Role drift for target={}: projection={} authoritative={}",
                    target.getId(), role, authoritative
            );
            throw new AppException(UserErrorCode.ROLE_UNVERIFIED);
        }
    }

    private List<Map<String, Object>> collectBlockers(UserProfile target, PrimaryRole role) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        if (role == PrimaryRole.DRIVER) {
            long count = countDriverBookings(target.getId());
            if (count > 0) {
                blockers.add(Map.of("code", "DRIVER_BOOKING_ACTIVE", "count", count));
            }
        }
        if (role == PrimaryRole.STAFF) {
            long count = countStaffTickets(target.getId());
            if (count > 0) {
                blockers.add(Map.of("code", "STAFF_TICKET_IN_PROGRESS", "count", count));
            }
        }
        return blockers;
    }

    /** Effective window UM-00 §5.2: PENDING còn hạn, CONFIRMED trước cutoff 15', CHECKED_IN/CHARGING luôn. */
    private long countDriverBookings(UUID driverId) {
        Instant now = Instant.now();
        Instant confirmedCutoff = now.plus(CONFIRMED_BLOCKER_LEAD);
        return entityManager.createQuery("""
                SELECT COUNT(b) FROM Booking b
                WHERE b.driver.id = :driverId
                  AND ((b.status = :pending AND (b.expiresAt IS NULL OR b.expiresAt > :now))
                       OR (b.status = :confirmed AND b.endAt > :cutoff)
                       OR b.status IN (:checkedIn, :charging))
                """, Long.class)
                .setParameter("driverId", driverId)
                .setParameter("pending", BookingStatus.PENDING)
                .setParameter("confirmed", BookingStatus.CONFIRMED)
                .setParameter("checkedIn", BookingStatus.CHECKED_IN)
                .setParameter("charging", BookingStatus.CHARGING)
                .setParameter("now", now)
                .setParameter("cutoff", confirmedCutoff)
                .getSingleResult();
    }

    private long countStaffTickets(UUID staffId) {
        return entityManager.createQuery("""
                SELECT COUNT(t) FROM SupportTicket t
                WHERE t.assignedHandler.id = :staffId AND t.status = :inProgress
                """, Long.class)
                .setParameter("staffId", staffId)
                .setParameter("inProgress", TicketStatus.IN_PROGRESS)
                .getSingleResult();
    }

    private void closeSessionsBestEffort(UserProfile target) {
        try {
            sessionRegistry.closeSessionsByPrincipal(target.getKeycloakId(), "account suspended");
        } catch (Exception ex) {
            log.warn("Failed to close WebSocket sessions for suspended user {}", target.getId(), ex);
        }
    }

    private AdminUserStatusResponse snapshot(UserProfile target, UUID commandId, boolean changed) {
        return new AdminUserStatusResponse(
                target.getId(),
                target.getStatus().name(),
                target.getVersion(),
                changed,
                commandId
        );
    }
}
