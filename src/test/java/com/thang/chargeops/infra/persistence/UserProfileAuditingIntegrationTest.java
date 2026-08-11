package com.thang.chargeops.infra.persistence;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import({JpaAuditingConfig.class, CurrentActorContext.class})
class UserProfileAuditingIntegrationTest {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private CurrentActorContext currentActorContext;

    @AfterEach
    void clearCurrentActor() {
        currentActorContext.clear();
    }

    @Test
    void usesInternalProfileIdForUpdatesWithoutRecursiveFlush() {
        UUID keycloakId = UUID.randomUUID();

        UserProfile profile = UserProfile.builder()
                .keycloakId(keycloakId.toString())
                .email("driver@chargeops.test")
                .status(UserStatus.ACTIVE)
                .build();

        UserProfile created = userProfileRepository.saveAndFlush(profile);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCreatedBy()).isNull();
        assertThat(created.getUpdatedBy()).isNull();

        currentActorContext.set(created.getId());
        created.setDisplayName("Driver One");
        created.setPhone("+84912345678");
        userProfileRepository.saveAndFlush(created);

        UserProfile updated = userProfileRepository.findById(created.getId()).orElseThrow();
        assertThat(updated.getDisplayName()).isEqualTo("Driver One");
        assertThat(updated.getPhone()).isEqualTo("+84912345678");
        assertThat(updated.getUpdatedBy()).isEqualTo(created.getId());
    }
}
