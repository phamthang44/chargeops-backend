package com.thang.chargeops.profile.repository;

import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.profile.model.PrimaryRole;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    List<UserProfile> findAllByStatusAndDeletedAtIsNull(UserStatus status);

    List<UserProfile> findAllByPrimaryRoleAndStatusAndDeletedAtIsNull(
            PrimaryRole primaryRole, UserStatus status);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserProfile u WHERE u.id = :id")
    Optional<UserProfile> findByIdWithLock(@Param("id") UUID id);

    /** Global search (admin only): displayName / email, đã lowercase + escape LIKE. */
    @Query("""
            SELECT u FROM UserProfile u
            WHERE u.deletedAt IS NULL
              AND (lower(u.displayName) LIKE :pattern ESCAPE '\\'
                   OR lower(u.email) LIKE :pattern ESCAPE '\\')
            ORDER BY u.displayName ASC
            """)
    List<UserProfile> searchProfiles(@Param("pattern") String pattern, Pageable pageable);

    // ── UM-01: admin user management read ──────────────────────────────────

    @Query("""
            SELECT u FROM UserProfile u
            WHERE u.deletedAt IS NULL
              AND (:pattern IS NULL
                   OR lower(u.displayName) LIKE :pattern ESCAPE '\\'
                   OR lower(u.email) LIKE :pattern ESCAPE '\\')
              AND (:role IS NULL OR u.primaryRole = :role)
              AND (:status IS NULL OR u.status = :status)
            ORDER BY u.createdAt DESC, u.id DESC
            """)
    Page<UserProfile> searchAdminPage(
            @Param("pattern") String pattern,
            @Param("role") PrimaryRole role,
            @Param("status") UserStatus status,
            Pageable pageable);

    @Query("""
            SELECT COUNT(u) FROM UserProfile u
            WHERE u.deletedAt IS NULL
              AND (:pattern IS NULL
                   OR lower(u.displayName) LIKE :pattern ESCAPE '\\'
                   OR lower(u.email) LIKE :pattern ESCAPE '\\')
              AND (:role IS NULL OR u.primaryRole = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    long countAdminScope(
            @Param("pattern") String pattern,
            @Param("role") PrimaryRole role,
            @Param("status") UserStatus status);

    @Query("""
            SELECT u.primaryRole, COUNT(u) FROM UserProfile u
            WHERE u.deletedAt IS NULL
              AND (:pattern IS NULL
                   OR lower(u.displayName) LIKE :pattern ESCAPE '\\'
                   OR lower(u.email) LIKE :pattern ESCAPE '\\')
              AND (:status IS NULL OR u.status = :status)
            GROUP BY u.primaryRole
            """)
    List<Object[]> countGroupByPrimaryRole(
            @Param("pattern") String pattern,
            @Param("status") UserStatus status);

    @Query("""
            SELECT u.status, COUNT(u) FROM UserProfile u
            WHERE u.deletedAt IS NULL
              AND (:pattern IS NULL
                   OR lower(u.displayName) LIKE :pattern ESCAPE '\\'
                   OR lower(u.email) LIKE :pattern ESCAPE '\\')
              AND (:role IS NULL OR u.primaryRole = :role)
            GROUP BY u.status
            """)
    List<Object[]> countGroupByStatus(
            @Param("pattern") String pattern,
            @Param("role") PrimaryRole role);
}
