package com.thang.chargeops.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** UM-01: item list/detail của admin user management (contract UM-00 §6.2). */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminUserProfileResponse {

    private UUID profileId;
    private String displayName;
    private String email;
    /** Tên `PrimaryRole` (DRIVER/OWNER/ADMIN/STAFF/UNKNOWN/CONFLICT). */
    private String role;
    /** "OK" khi role xác định; ngược lại tên chính nó (UNKNOWN/CONFLICT). */
    private String primaryRoleState;
    /** ACTIVE/SUSPENDED. */
    private String status;
    private String statusReason;
    private Instant statusChangedAt;
    /** Display name người thực hiện lần đổi trạng thái gần nhất (không lộ UUID nội bộ). */
    private String statusChangedBy;
    private Instant profileCreatedAt;
    private Instant roleSyncedAt;
    private Long version;
    /** VIEW_DETAIL + SUSPEND/RESUME theo role/status/self (UM-00 §6.2). */
    private List<String> actions;
}
