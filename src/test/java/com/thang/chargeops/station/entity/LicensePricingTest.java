package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.Plan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LicensePricingTest {

    @Test
    void derivesMonthlyFeeFromServerSidePlan() {
        License license = License.issue(
                new Station(),
                Plan.MONTHLY,
                Instant.parse("2026-08-17T00:00:00Z"),
                "LIC-001000"
        );

        assertThat(license.getFeeAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(500_000));
    }

    @Test
    void derivesYearlyFeeFromServerSidePlan() {
        License license = License.issue(
                new Station(),
                Plan.YEARLY,
                Instant.parse("2026-08-17T00:00:00Z"),
                "LIC-001001"
        );

        assertThat(license.getFeeAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }
}
