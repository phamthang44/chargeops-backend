package com.thang.chargeops.station.service.support;

import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.exception.violation.StationPricingViolation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class StationPricingExceptionTranslatorTest {

    private final StationPricingExceptionTranslator translator =
            new StationPricingExceptionTranslator();

    @Test
    void mapsEveryDomainViolationToAStableStationErrorCode() {
        Map<StationPricingViolation, StationErrorCode> expectedMappings = Map.ofEntries(
                entry(
                        StationPricingViolation.MIN_BOOKING_DURATION_INVALID,
                        StationErrorCode.PRICING_MIN_BOOKING_DURATION_INVALID
                ),
                entry(
                        StationPricingViolation.MIN_BOOKING_DURATION_90_NOT_SUPPORTED,
                        StationErrorCode.PRICING_MIN_BOOKING_DURATION_90_NOT_SUPPORTED
                ),
                entry(StationPricingViolation.BASE_PRICE_INVALID, StationErrorCode.PRICING_BASE_PRICE_INVALID),
                entry(StationPricingViolation.OPERATING_WEEK_INVALID, StationErrorCode.PRICING_OPERATING_WEEK_INVALID),
                entry(
                        StationPricingViolation.OPEN_24_HOURS_PERIOD_NOT_ALLOWED,
                        StationErrorCode.PRICING_OPEN_24_HOURS_PERIOD_NOT_ALLOWED
                ),
                entry(StationPricingViolation.CLOSED_DAY_TIME_PRESENT, StationErrorCode.PRICING_CLOSED_DAY_TIME_PRESENT),
                entry(StationPricingViolation.OPEN_DAY_TIME_REQUIRED, StationErrorCode.PRICING_OPEN_DAY_TIME_REQUIRED),
                entry(
                        StationPricingViolation.OPERATING_WINDOW_AMBIGUOUS,
                        StationErrorCode.PRICING_OPERATING_WINDOW_AMBIGUOUS
                ),
                entry(StationPricingViolation.TOU_RULES_REQUIRED, StationErrorCode.PRICING_TOU_RULES_REQUIRED),
                entry(StationPricingViolation.TOU_NAME_REQUIRED, StationErrorCode.PRICING_TOU_NAME_REQUIRED),
                entry(StationPricingViolation.TOU_NAME_DUPLICATED, StationErrorCode.PRICING_TOU_NAME_DUPLICATED),
                entry(StationPricingViolation.TOU_WINDOW_INVALID, StationErrorCode.PRICING_TOU_WINDOW_INVALID),
                entry(StationPricingViolation.TOU_RATE_INVALID, StationErrorCode.PRICING_TOU_RATE_INVALID),
                entry(StationPricingViolation.TOU_RULES_OVERLAP, StationErrorCode.PRICING_TOU_RULES_OVERLAP),
                entry(
                        StationPricingViolation.CONFIGURATION_CONFLICT,
                        StationErrorCode.PRICING_CONFIGURATION_CONFLICT
                )
        );

        assertThat(expectedMappings.keySet())
                .containsExactlyInAnyOrder(StationPricingViolation.values());
        expectedMappings.forEach((violation, errorCode) ->
                assertThat(translator.translate(new StationPricingDomainException(
                        violation,
                        "domain detail"
                )).getErrorCode()).isEqualTo(errorCode)
        );
    }
}
