package com.thang.chargeops.profile;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "phone", length = 20, nullable = false)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private UserStatus status;

}
