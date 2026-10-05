package com.thang.chargeops.station.staff.service.impl;

import com.thang.chargeops.exception.errorcode.StaffInvitationErrorCode;
import com.thang.chargeops.station.staff.dto.StaffInvitationResponse;
import com.thang.chargeops.station.staff.entity.StaffInvitation;
import com.thang.chargeops.station.staff.entity.StaffInvitationStatus;
import com.thang.chargeops.station.staff.repository.StaffInvitationRepository;
import com.thang.chargeops.station.staff.service.StaffIdentityProvisioner;
import com.thang.chargeops.station.staff.service.StaffInvitationService;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.infra.security.JwtClaimExtractor;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StaffInvitationServiceImpl implements StaffInvitationService {
    private static final Duration INVITATION_LIFETIME = Duration.ofDays(7);
    private final StaffInvitationRepository invitations;
    private final StationRepository stations;
    private final UserProfileRepository profiles;
    private final StationStaffAssignmentRepository assignments;
    private final CurrentProfileProvider currentProfile;
    private final StaffIdentityProvisioner identity;
    private final IdentityRoleService identityRoles;
    private final UserProfileService profileService;
    private final JwtClaimExtractor jwtClaims;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Override
    public StaffInvitationResponse invite(UUID stationId, String email) {
        String normalized = normalizeEmail(email);
        UUID invitationId = transactions().execute(status -> {
            Station station = requireOwnedActiveStation(stationId);
            if (profiles.findByEmail(normalized).isPresent()) throw new AppException(StaffInvitationErrorCode.EMAIL_IN_USE);
            Instant now = clock.instant();
            try {
                StaffInvitation invitation = invitations.saveAndFlush(StaffInvitation.pending(
                        station, normalized, currentProfile.requireProfileId(), now, now.plus(INVITATION_LIFETIME)));
                return invitation.getId();
            } catch (DataIntegrityViolationException exception) {
                throw new AppException(StaffInvitationErrorCode.EMAIL_IN_USE);
            }
        });
        if (invitationId == null) throw new IllegalStateException("Invitation transaction returned no ID");
        return deliver(stationId, invitationId);
    }

    @Override
    public StaffInvitationResponse resend(UUID stationId, UUID invitationId) {
        return deliver(stationId, invitationId);
    }

    private StaffInvitationResponse deliver(UUID stationId, UUID invitationId) {
        DeliveryTarget target = transactions().execute(status -> {
            requireOwnedStation(stationId);
            StaffInvitation invitation = invitations.findById(invitationId)
                    .orElseThrow(() -> new AppException(StaffInvitationErrorCode.NOT_FOUND));
            if (!stationId.equals(invitation.getStation().getId())) throw new AppException(StaffInvitationErrorCode.NOT_FOUND);
            if (invitation.getStatus() != StaffInvitationStatus.PENDING
                    && invitation.getStatus() != StaffInvitationStatus.SENT) {
                throw new AppException(StaffInvitationErrorCode.STATE_CONFLICT);
            }
            return new DeliveryTarget(invitation.getId(), invitation.getEmail());
        });
        if (target == null) throw new IllegalStateException("Invitation lookup returned no result");
        String identityUserId = identity.ensureStaffIdentity(target.id(), target.email());
        identity.sendActivationEmail(identityUserId);
        StaffInvitationResponse result = transactions().execute(status -> {
            requireOwnedActiveStation(stationId);
            StaffInvitation invitation = invitations.findByIdForUpdate(invitationId)
                    .orElseThrow(() -> new AppException(StaffInvitationErrorCode.NOT_FOUND));
            if (!stationId.equals(invitation.getStation().getId())
                    || invitation.getStatus() == StaffInvitationStatus.CANCELLED) {
                throw new AppException(StaffInvitationErrorCode.STATE_CONFLICT);
            }
            Instant now = clock.instant();
            invitation.sent(identityUserId, now, now.plus(INVITATION_LIFETIME));
            return StaffInvitationResponse.from(invitations.saveAndFlush(invitation));
        });
        if (result == null) throw new IllegalStateException("Invitation delivery returned no result");
        return result;
    }

    @Transactional(readOnly = true)
    @Override
    public Page<StaffInvitationResponse> list(UUID stationId, int page, int size) {
        requireOwnedStation(stationId);
        return invitations.findByStation_IdOrderByCreatedAtDesc(stationId, PageRequest.of(page - 1, size))
                .map(StaffInvitationResponse::from);
    }

    @Transactional
    @Override
    public StaffInvitationResponse cancel(UUID stationId, UUID invitationId) {
        requireOwnedStation(stationId);
        StaffInvitation invitation = invitations.findByIdForUpdate(invitationId)
                .orElseThrow(() -> new AppException(StaffInvitationErrorCode.NOT_FOUND));
        if (!stationId.equals(invitation.getStation().getId())) throw new AppException(StaffInvitationErrorCode.NOT_FOUND);
        if (invitation.getStatus() != StaffInvitationStatus.PENDING
                && invitation.getStatus() != StaffInvitationStatus.SENT) {
            throw new AppException(StaffInvitationErrorCode.STATE_CONFLICT);
        }
        invitation.cancel(clock.instant());
        return StaffInvitationResponse.from(invitations.saveAndFlush(invitation));
    }

    @Transactional
    @Override
    public StaffInvitationResponse activate(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new AppException(StaffInvitationErrorCode.IDENTITY_CONFLICT);
        }
        String identityUserId = jwtClaims.requireSubject(jwt);
        String email = normalizeEmail(jwtClaims.requireEmail(jwt));
        if (!identityRoles.getRoles(identityUserId).equals(Set.of(Role.STAFF))) {
            throw new AppException(StaffInvitationErrorCode.IDENTITY_CONFLICT);
        }
        StaffInvitation invitation = invitations.findByIdentityUserIdAndStatusForUpdate(
                        identityUserId, StaffInvitationStatus.SENT)
                .orElseGet(() -> invitations.findByIdentityUserId(identityUserId)
                        .filter(existing -> existing.getStatus() == StaffInvitationStatus.ACCEPTED)
                        .orElseThrow(() -> new AppException(StaffInvitationErrorCode.NOT_FOUND)));
        profileService.getOrBootstrapProfile(jwt);
        if (invitation.getStatus() == StaffInvitationStatus.ACCEPTED) {
            var acceptedProfile = profiles.findByKeycloakId(identityUserId)
                    .orElseThrow(() -> new AppException(StaffInvitationErrorCode.IDENTITY_CONFLICT));
            if (acceptedProfile.getStatus() != UserStatus.ACTIVE
                    || !assignments.existsByStation_IdAndStaff_IdAndStatus(
                    invitation.getStation().getId(), acceptedProfile.getId(), StaffAssignmentStatus.ACTIVE)) {
                throw new AppException(StaffInvitationErrorCode.STATE_CONFLICT);
            }
            return StaffInvitationResponse.from(invitation);
        }
        Instant now = clock.instant();
        if (!now.isBefore(invitation.getExpiresAt()) || !email.equals(invitation.getEmail())) {
            throw new AppException(StaffInvitationErrorCode.IDENTITY_CONFLICT);
        }
        Station station = invitation.getStation();
        if (station.getStatus() != StationStatus.ACTIVE
                || !Objects.equals(station.getOwner().getId(), invitation.getInvitedBy())) {
            throw new AppException(StaffInvitationErrorCode.STATION_UNAVAILABLE);
        }
        var profile = profiles.findByKeycloakId(identityUserId)
                .orElseThrow(() -> new AppException(StaffInvitationErrorCode.IDENTITY_CONFLICT));
        if (profile.getStatus() != UserStatus.ACTIVE
                || !email.equals(normalizeEmail(profile.getEmail()))
                || assignments.existsByStaff_IdAndStatus(
                profile.getId(), StaffAssignmentStatus.ACTIVE)) {
            throw new AppException(StaffInvitationErrorCode.STATE_CONFLICT);
        }
        try {
            assignments.saveAndFlush(StationStaffAssignment.builder()
                    .station(station).staff(profile).status(StaffAssignmentStatus.ACTIVE)
                    .assignedBy(invitation.getInvitedBy()).assignedAt(now).build());
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(StaffInvitationErrorCode.STATE_CONFLICT);
        }
        invitation.accept(now);
        return StaffInvitationResponse.from(invitations.saveAndFlush(invitation));
    }

    private Station requireOwnedStation(UUID stationId) {
        Station station = stations.findById(stationId)
                .orElseThrow(() -> new AppException(StaffInvitationErrorCode.NOT_FOUND));
        var owner = currentProfile.requireProfile();
        if (owner.getStatus() != UserStatus.ACTIVE || !station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(StaffInvitationErrorCode.NOT_FOUND);
        }
        return station;
    }

    private Station requireOwnedActiveStation(UUID stationId) {
        Station station = requireOwnedStation(stationId);
        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(StaffInvitationErrorCode.STATION_UNAVAILABLE);
        }
        return station;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) throw new AppException(StaffInvitationErrorCode.IDENTITY_CONFLICT);
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private record DeliveryTarget(UUID id, String email) { }
}
