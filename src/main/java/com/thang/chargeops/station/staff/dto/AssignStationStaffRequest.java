package com.thang.chargeops.station.staff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static com.thang.chargeops.common.constant.CommonConfig.MAX_LENGTH_EMAIL;

public record AssignStationStaffRequest(
        @NotBlank(message = "Staff email is required")
        @Email(message = "Staff email must be valid")
        @Size(max = MAX_LENGTH_EMAIL, message = "Staff email is too long")
        String email,

        @Size(max = 500, message = "Assignment note cannot exceed 500 characters")
        String note
) {
}
