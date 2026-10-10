package com.thang.chargeops.profile.dto;

import com.thang.chargeops.common.enums.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** UM-02: body PATCH /admin/users/{id}/status (UM-00 §6.1). */
public record AdminUserStatusRequest(
        @NotNull UserStatus status,
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 500) String reason,
        @NotNull UUID commandId
) {
}
