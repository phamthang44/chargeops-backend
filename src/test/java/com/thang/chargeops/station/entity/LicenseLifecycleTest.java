package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.station.exception.LicenseDomainException;
import com.thang.chargeops.station.exception.LicenseViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseLifecycleTest {

    private static final Instant START_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant ACTIVE_AT = Instant.parse("2026-08-20T00:00:01Z");

    @Test
    void suspendsActiveLicenseInsideEffectiveWindow() {
        License license = activeLicense();

        license.suspend(ACTIVE_AT.plusSeconds(1));

        assertThat(license.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
    }

    @Test
    void rejectsSuspendFromNonActiveStatus() {
        License license = License.issue(
                new Station(),
                Plan.MONTHLY,
                START_AT,
                "LIC-001000"
        );

        assertThatThrownBy(() -> license.suspend(ACTIVE_AT))
                .isInstanceOfSatisfying(
                        LicenseDomainException.class,
                        exception -> assertThat(exception.getViolation())
                                .isEqualTo(LicenseViolation.INVALID_TRANSITION)
                );
    }

    @Test
    void rejectsSuspendAtExclusiveExpirationBoundary() {
        License license = activeLicense();

        assertThatThrownBy(() -> license.suspend(license.getExpiresAt()))
                .isInstanceOfSatisfying(
                        LicenseDomainException.class,
                        exception -> assertThat(exception.getViolation())
                                .isEqualTo(LicenseViolation.OUTSIDE_EFFECTIVE_WINDOW)
                );
    }

    @Test
    void cancelsActiveLicenseAsTerminalTransition() {
        License license = activeLicense();

        license.cancel();

        assertThat(license.getStatus()).isEqualTo(LicenseStatus.CANCELLED);
    }

    @Test
    void rejectsCancellingTerminalLicense() {
        License license = activeLicense();
        license.cancel();

        assertThatThrownBy(license::cancel)
                .isInstanceOfSatisfying(
                        LicenseDomainException.class,
                        exception -> assertThat(exception.getViolation())
                                .isEqualTo(LicenseViolation.TERMINAL_LICENSE)
                );
    }

    private License activeLicense() {
        License license = License.issue(
                new Station(),
                Plan.MONTHLY,
                START_AT,
                "LIC-001000"
        );
        license.activate(ACTIVE_AT);
        return license;
    }
}
