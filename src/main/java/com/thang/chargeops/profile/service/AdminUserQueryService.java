package com.thang.chargeops.profile.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.profile.dto.AdminUserProfileResponse;
import com.thang.chargeops.profile.dto.AdminUserSummaryResponse;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.model.PrimaryRole;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** UM-01: read API admin user management (list/summary/detail — UM-00 §6). */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserQueryService {

    private static final String ACTION_VIEW_DETAIL = "VIEW_DETAIL";
    private static final String ACTION_SUSPEND = "SUSPEND";
    private static final String ACTION_RESUME = "RESUME";

    private final UserProfileRepository userProfileRepository;
    private final CurrentProfileProvider currentProfile;

    @Transactional(readOnly = true)
    public Page<AdminUserProfileResponse> list(int page, int size, String q, PrimaryRole role, UserStatus status) {
        UUID actorId = requireActiveAdminProfileId();
        Page<UserProfile> profiles = userProfileRepository.searchAdminPage(
                likePattern(q), role, status, PageRequest.of(page - 1, size));
        Map<UUID, String> changedByNames = loadDisplayNames(profiles.getContent());
        return profiles.map(profile -> toResponse(profile, changedByNames, actorId));
    }

    @Transactional(readOnly = true)
    public AdminUserSummaryResponse summary(String q, PrimaryRole role, UserStatus status) {
        requireActiveAdminProfileId();
        String pattern = likePattern(q);

        Map<String, Long> byRole = new LinkedHashMap<>();
        for (PrimaryRole value : PrimaryRole.values()) {
            byRole.put(value.name(), 0L);
        }
        for (Object[] row : userProfileRepository.countGroupByPrimaryRole(pattern, status)) {
            byRole.put(((PrimaryRole) row[0]).name(), (Long) row[1]);
        }

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (UserStatus value : UserStatus.values()) {
            byStatus.put(value.name(), 0L);
        }
        for (Object[] row : userProfileRepository.countGroupByStatus(pattern, role)) {
            byStatus.put(((UserStatus) row[0]).name(), (Long) row[1]);
        }

        return AdminUserSummaryResponse.builder()
                .scope(AdminUserSummaryResponse.Scope.builder()
                        .q(StringUtils.hasText(q) ? q.trim() : null)
                        .role(role == null ? null : role.name())
                        .status(status == null ? null : status.name())
                        .build())
                .totalProfiles(userProfileRepository.countAdminScope(pattern, role, status))
                .byRole(byRole)
                .byStatus(byStatus)
                .evaluatedAt(Instant.now())
                .build();
    }

    @Transactional(readOnly = true)
    public AdminUserProfileResponse detail(UUID profileId) {
        UUID actorId = requireActiveAdminProfileId();
        UserProfile profile = userProfileRepository.findById(profileId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));
        Map<UUID, String> changedByNames = loadDisplayNames(List.of(profile));
        return toResponse(profile, changedByNames, actorId);
    }

    private UUID requireActiveAdminProfileId() {
        UserProfile actor = currentProfile.requireProfile();
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ProfileErrorCode.PROFILE_NOT_ACTIVE);
        }
        return actor.getId();
    }

    private Map<UUID, String> loadDisplayNames(List<UserProfile> profiles) {
        Set<UUID> ids = profiles.stream()
                .map(UserProfile::getStatusChangedBy)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userProfileRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserProfile::getId, p -> nullToEmpty(p.getDisplayName()), (a, b) -> a));
    }

    private AdminUserProfileResponse toResponse(
            UserProfile profile, Map<UUID, String> changedByNames, UUID actorId) {
        PrimaryRole role = profile.getPrimaryRole();
        boolean self = profile.getId().equals(actorId);
        boolean mutableRole = role == PrimaryRole.DRIVER || role == PrimaryRole.STAFF;
        List<String> actions = new ArrayList<>();
        actions.add(ACTION_VIEW_DETAIL);
        if (mutableRole && !self) {
            if (profile.getStatus() == UserStatus.ACTIVE) {
                actions.add(ACTION_SUSPEND);
            } else if (profile.getStatus() == UserStatus.SUSPENDED) {
                actions.add(ACTION_RESUME);
            }
        }
        String statusChangedBy = profile.getStatusChangedBy() == null
                ? null
                : changedByNames.getOrDefault(profile.getStatusChangedBy(), null);
        return AdminUserProfileResponse.builder()
                .profileId(profile.getId())
                .displayName(profile.getDisplayName())
                .email(profile.getEmail())
                .role(role.name())
                .primaryRoleState(isKnownRole(role) ? "OK" : role.name())
                .status(profile.getStatus() == null ? null : profile.getStatus().name())
                .statusReason(profile.getStatusReason())
                .statusChangedAt(profile.getStatusChangedAt())
                .statusChangedBy(statusChangedBy)
                .profileCreatedAt(profile.getCreatedAt())
                .roleSyncedAt(profile.getRoleSyncedAt())
                .version(profile.getVersion())
                .actions(actions)
                .build();
    }

    private static boolean isKnownRole(PrimaryRole role) {
        return role == PrimaryRole.DRIVER || role == PrimaryRole.OWNER
                || role == PrimaryRole.ADMIN || role == PrimaryRole.STAFF;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static String likePattern(String q) {
        if (!StringUtils.hasText(q)) {
            return null;
        }
        String escaped = q.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
