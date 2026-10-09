package com.thang.chargeops.profile.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.profile.model.PrimaryRole;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

import static com.thang.chargeops.common.constant.CommonConfig.MAX_LENGTH_EMAIL;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "user_profile", indexes = {
        @Index(columnList = "email")
})
public class UserProfile extends SoftDeletableEntity {

    @Column(name = "keycloak_id", unique = true,  nullable = false)
    private String keycloakId;

    @Column(name = "email", unique = true, nullable = false, length = MAX_LENGTH_EMAIL)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private UserStatus status;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_role", nullable = false, length = 16)
    private PrimaryRole primaryRole = PrimaryRole.UNKNOWN;

    @Column(name = "role_synced_at")
    private java.time.Instant roleSyncedAt;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "avatar_storage_key", length = 255)
    private String avatarStorageKey;

    @Builder.Default
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "status_changed_at")
    private java.time.Instant statusChangedAt;

    @Column(name = "status_changed_by")
    private UUID statusChangedBy;

}
