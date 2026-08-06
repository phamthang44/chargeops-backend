package com.thang.chargeops.profile.mapper;

import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.entity.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    @Mapping(target = "profileCompleted", expression = "java(isProfileCompleted(profile))")
    UserProfileResponse toResponse(UserProfile profile);

    default boolean isProfileCompleted(UserProfile profile) {
        return profile != null
                && hasText(profile.getDisplayName())
                && hasText(profile.getPhone());
    }

    default boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
