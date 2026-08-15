package com.thang.chargeops.profile.support;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CurrentProfileProvider {

    private final CurrentActorContext currentActorContext;
    private final UserProfileRepository userProfileRepository;

    public UUID requireProfileId() {
        return currentActorContext.get().orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));
    }

    public UserProfile requireProfile() {
        return userProfileRepository.findById(requireProfileId()).orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));
    }

    public UserProfile getProfileReference() {
        return userProfileRepository.getReferenceById(requireProfileId());
    }
}
