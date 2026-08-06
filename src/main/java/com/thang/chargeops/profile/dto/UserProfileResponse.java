package com.thang.chargeops.profile.dto;

import com.thang.chargeops.common.enums.UserStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserProfileResponse {

    private String id;
    private String keycloakId;
    private String email;
    private String displayName;
    private String phone;
    private UserStatus status;
    private boolean profileCompleted;

}
