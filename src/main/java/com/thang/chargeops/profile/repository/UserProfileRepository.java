package com.thang.chargeops.profile.repository;

import com.thang.chargeops.profile.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByKeycloakId(String keycloakId);

    Optional<UserProfile> findByEmail(String email);
}
