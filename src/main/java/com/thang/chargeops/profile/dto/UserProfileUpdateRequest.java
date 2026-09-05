package com.thang.chargeops.profile.dto;

import com.thang.chargeops.common.validator.PhoneNumber;
import com.thang.chargeops.exception.errormessage.ProfileErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserProfileUpdateRequest {

    @NotBlank(message = ProfileErrorMessage.DISPLAY_NAME_REQUIRED_KEY)
    @Size(max = 255, message = ProfileErrorMessage.DISPLAY_NAME_MAX_LENGTH_KEY)
    private String displayName;

    @NotBlank(message = ProfileErrorMessage.PHONE_REQUIRED_KEY)
    @Size(max = 20, message = ProfileErrorMessage.PHONE_MAX_LENGTH_KEY)
    @PhoneNumber
    private String phone;

    @Size(max = 500)
    private String avatarUrl;

    @Size(max = 255)
    private String avatarStorageKey;

}
