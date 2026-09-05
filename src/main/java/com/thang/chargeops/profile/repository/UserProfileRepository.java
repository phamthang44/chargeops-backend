package com.thang.chargeops.profile.repository;

import com.thang.chargeops.profile.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByKeycloakId(String keycloakId);

    @Query("select profile.id from UserProfile profile where profile.keycloakId = :keycloakId")
    Optional<UUID> findIdByKeycloakId(@Param("keycloakId") String keycloakId);

    @Modifying
    @Query(value = """
        INSERT INTO user_profile (keycloak_id, email, display_name, status, created_at, updated_at)
        VALUES (:keycloakId, :email, :displayName, 'ACTIVE', now(), now())
        ON CONFLICT (keycloak_id) DO NOTHING
        """, nativeQuery = true)
    int insertProfileIfAbsent(
            @Param("keycloakId") String keycloakId,
            @Param("email") String email,
            @Param("displayName") String displayName
    );

    Optional<UserProfile> findByEmail(String email);
}
