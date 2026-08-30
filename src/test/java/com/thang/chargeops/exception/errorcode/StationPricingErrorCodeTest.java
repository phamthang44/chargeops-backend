package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StationPricingErrorCodeTest {

    private static final List<StationErrorCode> PRICING_ERROR_CODES = List.of(
            StationErrorCode.PRICING_MIN_BOOKING_DURATION_INVALID,
            StationErrorCode.PRICING_OPERATING_WEEK_INVALID,
            StationErrorCode.PRICING_CLOSED_DAY_TIME_PRESENT,
            StationErrorCode.PRICING_OPEN_DAY_TIME_REQUIRED,
            StationErrorCode.PRICING_OPERATING_WINDOW_AMBIGUOUS,
            StationErrorCode.PRICING_TOU_RULES_REQUIRED,
            StationErrorCode.PRICING_TOU_NAME_DUPLICATED,
            StationErrorCode.PRICING_TOU_WINDOW_INVALID,
            StationErrorCode.PRICING_TOU_RATE_INVALID,
            StationErrorCode.PRICING_TOU_RULES_OVERLAP,
            StationErrorCode.PRICING_BASE_PRICE_INVALID,
            StationErrorCode.PRICING_TOU_NAME_REQUIRED,
            StationErrorCode.PRICING_OPEN_24_HOURS_PERIOD_NOT_ALLOWED
    );

    @Test
    void exposesStableBadRequestCodesAndRegisteredMessages() {
        assertThat(PRICING_ERROR_CODES)
                .extracting(StationErrorCode::getCode)
                .containsExactly(
                        "STATION_040",
                        "STATION_041",
                        "STATION_042",
                        "STATION_043",
                        "STATION_044",
                        "STATION_045",
                        "STATION_046",
                        "STATION_047",
                        "STATION_048",
                        "STATION_049",
                        "STATION_050",
                        "STATION_051",
                        "STATION_052"
                );

        assertThat(PRICING_ERROR_CODES).allSatisfy(errorCode -> {
            assertThat(errorCode.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ErrorMessage.defaultMessage(errorCode.getMessageKey()))
                    .isEqualTo(errorCode.getMessage());
        });
    }

    @Test
    void exposesPricingConfigurationConflict() {
        StationErrorCode errorCode = StationErrorCode.PRICING_CONFIGURATION_CONFLICT;

        assertThat(errorCode.getCode()).isEqualTo("STATION_053");
        assertThat(errorCode.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorMessage.defaultMessage(errorCode.getMessageKey()))
                .isEqualTo(errorCode.getMessage());
    }
}
