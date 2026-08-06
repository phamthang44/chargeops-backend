package com.thang.chargeops.profile.dto;

import com.thang.chargeops.common.validator.PhoneNumber;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class UserProfileUpdateRequest {

    @NotBlank(message = ErrorMessage.Validation.PROFILE_FULL_NAME_REQUIRED_KEY)
    private String fullName;

    @NotBlank(message = ErrorMessage.Validation.PROFILE_PHONE_REQUIRED_KEY)
    @Size(max = 20, message = ErrorMessage.Validation.PROFILE_PHONE_MAX_LENGTH_KEY)
    @PhoneNumber
    private String phone;

}
