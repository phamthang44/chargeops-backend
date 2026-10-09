package com.thang.chargeops.profile;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.profile.dto.AdminUserProfileResponse;
import com.thang.chargeops.profile.dto.AdminUserSummaryResponse;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.model.PrimaryRole;
import com.thang.chargeops.profile.service.AdminUserQueryService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(AdminUserQueryService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminUserQueryPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUserQueryService adminUsers;
    @MockitoBean CurrentProfileProvider currentProfile;

    UUID actorId;
    UUID driverActive;
    UUID driverSuspended;
    UUID ownerActive;
    UUID staffActive;
    UUID unknownActive;
    UUID deletedProfile;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM user_profile");
        actorId = profile("admin@test.local", "Admin", "ADMIN", "ACTIVE",
                Instant.parse("2026-10-09T01:00:00Z"), null);
        driverActive = profile("a@test.local", "Nguyen Van A", "DRIVER", "ACTIVE",
                Instant.parse("2026-10-09T02:00:00Z"), null);
        driverSuspended = profile("b@test.local", "Tran Thi B", "DRIVER", "SUSPENDED",
                Instant.parse("2026-10-09T03:00:00Z"), "Vi phạm");
        ownerActive = profile("c@test.local", "Le Van C", "OWNER", "ACTIVE",
                Instant.parse("2026-10-09T04:00:00Z"), null);
        staffActive = profile("d@test.local", "Staff D", "STAFF", "ACTIVE",
                Instant.parse("2026-10-09T05:00:00Z"), null);
        unknownActive = profile("e@test.local", "Ghost E", "UNKNOWN", "ACTIVE",
                Instant.parse("2026-10-09T06:00:00Z"), null);
        deletedProfile = profile("f@test.local", "Deleted F", "DRIVER", "ACTIVE",
                Instant.parse("2026-10-09T07:00:00Z"), null);
        jdbc.update("UPDATE user_profile SET deleted_at = now() WHERE id = ?", deletedProfile);

        UserProfile actor = new UserProfile();
        actor.setId(actorId);
        actor.setStatus(UserStatus.ACTIVE);
        when(currentProfile.requireProfile()).thenReturn(actor);
    }

    @Test
    void listFiltersPaginatesAndExcludesDeleted() {
        var all = adminUsers.list(1, 20, null, null, null);
        assertThat(all.getTotalElements()).isEqualTo(6);
        assertThat(all.getContent()).extracting(AdminUserProfileResponse::getProfileId)
                .doesNotContain(deletedProfile);
        // sort createdAt DESC
        assertThat(all.getContent().getFirst().getProfileId()).isEqualTo(unknownActive);
        assertThat(all.getContent()).extracting(AdminUserProfileResponse::getRole)
                .contains("UNKNOWN");

        assertThat(adminUsers.list(1, 20, null, PrimaryRole.DRIVER, null).getTotalElements()).isEqualTo(2);
        assertThat(adminUsers.list(1, 20, null, null, UserStatus.SUSPENDED).getTotalElements()).isEqualTo(1);
        assertThat(adminUsers.list(1, 20, "nguyen", null, null).getTotalElements()).isEqualTo(1);
        assertThat(adminUsers.list(1, 20, "NGUYEN", null, null).getTotalElements()).isEqualTo(1);
        // LIKE wildcard trong q được escape, không match all
        assertThat(adminUsers.list(1, 20, "%", null, null).getTotalElements()).isEqualTo(0);
    }

    @Test
    void summaryCountsFullScopeIndependentlyOfPageAndIncludesZeroKeys() {
        AdminUserSummaryResponse all = adminUsers.summary(null, null, null);
        assertThat(all.getTotalProfiles()).isEqualTo(6);
        assertThat(all.getByRole().get("DRIVER")).isEqualTo(2);
        assertThat(all.getByRole().get("UNKNOWN")).isEqualTo(1);
        assertThat(all.getByRole()).containsOnlyKeys("DRIVER", "OWNER", "ADMIN", "STAFF", "UNKNOWN", "CONFLICT");
        assertThat(all.getByStatus().get("ACTIVE")).isEqualTo(5);
        assertThat(all.getByStatus().get("SUSPENDED")).isEqualTo(1);

        // status filter: totalProfiles theo filter nhưng byRole/byStatus vẫn cross-tab hữu dụng
        AdminUserSummaryResponse suspended = adminUsers.summary(null, null, UserStatus.SUSPENDED);
        assertThat(suspended.getTotalProfiles()).isEqualTo(1);
        assertThat(suspended.getByRole().get("DRIVER")).isEqualTo(1);
        assertThat(suspended.getByRole().get("OWNER")).isZero();
        assertThat(suspended.getByStatus().get("SUSPENDED")).isEqualTo(1);
        assertThat(suspended.getByStatus().get("ACTIVE")).isEqualTo(5);
    }

    @Test
    void detailReturnsStatusFieldsAndHidesDeleted() {
        AdminUserProfileResponse detail = adminUsers.detail(driverSuspended);
        assertThat(detail.getStatus()).isEqualTo("SUSPENDED");
        assertThat(detail.getStatusReason()).isEqualTo("Vi phạm");
        assertThat(detail.getPrimaryRoleState()).isEqualTo("OK");
        assertThat(detail.getVersion()).isZero();

        assertThatThrownBy(() -> adminUsers.detail(deletedProfile))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProfileErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void actionsFollowRoleStatusAndSelfRules() {
        assertThat(adminUsers.detail(ownerActive).getActions()).containsExactly("VIEW_DETAIL");
        assertThat(adminUsers.detail(driverActive).getActions()).containsExactly("VIEW_DETAIL", "SUSPEND");
        assertThat(adminUsers.detail(driverSuspended).getActions()).containsExactly("VIEW_DETAIL", "RESUME");
        assertThat(adminUsers.detail(actorId).getActions()).containsExactly("VIEW_DETAIL");
        assertThat(adminUsers.detail(unknownActive).getActions()).containsExactly("VIEW_DETAIL");
        assertThat(adminUsers.detail(staffActive).getActions()).containsExactly("VIEW_DETAIL", "SUSPEND");
    }

    private UUID profile(String email, String name, String role, String status, Instant createdAt, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_profile(id, keycloak_id, email, display_name, status, primary_role,
                    status_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, id.toString(), email, name, status, role, reason,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }
}
