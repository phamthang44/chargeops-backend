package com.thang.chargeops.booking.checkin;

import com.thang.chargeops.common.constant.SecurityConstants;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ChargerSimulatorSecurityContractTest {

    @Test
    void simulatorControllerExistsOnlyInDemoAndTestProfiles() {
        Profile profile = ChargerSimulatorController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("demo", "test");
    }

    @Test
    void simulatorChallengeRequiresAdminRole() throws NoSuchMethodException {
        Method method = ChargerSimulatorController.class
                .getDeclaredMethod("checkInChallenge", String.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void internalNamespaceIsNotPublic() {
        assertThat(Arrays.asList(SecurityConstants.publicEndpoints()))
                .noneMatch(pattern -> pattern.startsWith("/api/v1/internal"));
    }
}
