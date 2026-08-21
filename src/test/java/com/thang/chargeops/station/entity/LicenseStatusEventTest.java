package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusActorType;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.profile.entity.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LicenseStatusEventTest {

    private static final Instant PERFORMED_AT = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    void recordsUserEventWithPerformerAndNormalizedReason() {
        License license = mock(License.class);
        UserProfile admin = mock(UserProfile.class);

        LicenseStatusEvent event = LicenseStatusEvent.recordedByUser(
                license,
                LicenseStatusEventType.SUSPENDED,
                LicenseStatus.ACTIVE,
                LicenseStatus.SUSPENDED,
                admin,
                PERFORMED_AT,
                "  Manual compliance review  "
        );

        assertThat(event.getLicense()).isSameAs(license);
        assertThat(event.getEventType()).isEqualTo(LicenseStatusEventType.SUSPENDED);
        assertThat(event.getFromStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(event.getToStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(event.getActorType()).isEqualTo(LicenseStatusActorType.USER);
        assertThat(event.getPerformedBy()).isSameAs(admin);
        assertThat(event.getPerformedAt()).isEqualTo(PERFORMED_AT);
        assertThat(event.getReason()).isEqualTo("Manual compliance review");
    }

    @Test
    void recordsSystemEventWithoutUser() {
        License license = mock(License.class);

        LicenseStatusEvent event = LicenseStatusEvent.recordedBySystem(
                license,
                LicenseStatusEventType.EXPIRED,
                LicenseStatus.ACTIVE,
                LicenseStatus.EXPIRED,
                PERFORMED_AT,
                null
        );

        assertThat(event.getActorType()).isEqualTo(LicenseStatusActorType.SYSTEM);
        assertThat(event.getPerformedBy()).isNull();
        assertThat(event.getReason()).isNull();
    }

    @Test
    void rejectsTransitionThatDoesNotMatchEventType() {
        License license = mock(License.class);

        assertThatThrownBy(() -> LicenseStatusEvent.recordedBySystem(
                license,
                LicenseStatusEventType.ACTIVATED,
                LicenseStatus.SUSPENDED,
                LicenseStatus.ACTIVE,
                PERFORMED_AT,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid license status event transition");
    }

    @Test
    void rejectsUserEventWithoutPerformer() {
        License license = mock(License.class);

        assertThatThrownBy(() -> LicenseStatusEvent.recordedByUser(
                license,
                LicenseStatusEventType.ISSUED,
                null,
                LicenseStatus.PENDING,
                null,
                PERFORMED_AT,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("License status event performer cannot be null");
    }
}
