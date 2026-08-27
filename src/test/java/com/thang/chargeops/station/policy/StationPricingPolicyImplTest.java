package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.policy.impl.StationPricingPolicyImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationPricingPolicyImplTest {

    private final StationPricingPolicyImpl policy = new StationPricingPolicyImpl();

    @Test
    void acceptsOnlyExplicitMinimumDurationPresets() {
        assertThatCode(() -> policy.validateBookingSettings(30)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateBookingSettings(60)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateBookingSettings(90)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateBookingSettings(45))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_MIN_BOOKING_DURATION_INVALID)
                );
    }

    @Test
    void acceptsOperatingWindowThatCrossesMidnight() {
        List<UpdateStationPricingRequest.OperatingHourRequest> hours =
                Arrays.stream(StationDayOfWeek.values())
                        .map(day -> new UpdateStationPricingRequest.OperatingHourRequest(
                                day,
                                LocalTime.of(22, 0),
                                LocalTime.of(2, 0),
                                true
                        ))
                        .toList();

        assertThatCode(() -> policy.validateOperatingHours(false, hours))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateOperatingDay() {
        List<UpdateStationPricingRequest.OperatingHourRequest> hours =
                Arrays.stream(StationDayOfWeek.values())
                        .map(day -> new UpdateStationPricingRequest.OperatingHourRequest(
                                StationDayOfWeek.MONDAY,
                                LocalTime.of(6, 0),
                                LocalTime.of(23, 0),
                                true
                        ))
                        .toList();

        assertThatThrownBy(() -> policy.validateOperatingHours(false, hours))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_OPERATING_WEEK_INVALID)
                );
    }

    @Test
    void rejectsSemanticOverlapBetweenDailyAndWeekday() {
        List<UpdateStationPricingRequest.TouRuleRequest> rules = List.of(
                rule("Mỗi ngày", TouRateDayType.DAILY, "17:00", "21:00"),
                rule("Ngày thường", TouRateDayType.WEEKDAY, "18:00", "20:00")
        );

        assertThatThrownBy(() -> policy.validateTouRules(rules))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_TOU_RULES_OVERLAP)
                );
    }

    @Test
    void acceptsSameWindowForWeekdayAndWeekend() {
        List<UpdateStationPricingRequest.TouRuleRequest> rules = List.of(
                rule("Ngày thường", TouRateDayType.WEEKDAY, "17:00", "21:00"),
                rule("Cuối tuần", TouRateDayType.WEEKEND, "17:00", "21:00")
        );

        assertThatCode(() -> policy.validateTouRules(rules)).doesNotThrowAnyException();
    }

    @Test
    void detectsOverlapAcrossMidnightAndWeekBoundary() {
        List<UpdateStationPricingRequest.TouRuleRequest> rules = List.of(
                rule("Đêm", TouRateDayType.DAILY, "21:00", "05:00"),
                rule("Sáng sớm", TouRateDayType.WEEKEND, "04:30", "06:00")
        );

        assertThatThrownBy(() -> policy.validateTouRules(rules))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_TOU_RULES_OVERLAP)
                );
    }

    private UpdateStationPricingRequest.TouRuleRequest rule(
            String name,
            TouRateDayType dayType,
            String start,
            String end
    ) {
        return new UpdateStationPricingRequest.TouRuleRequest(
                null,
                name,
                TouRatePeriodCode.PEAK,
                dayType,
                LocalTime.parse(start),
                LocalTime.parse(end),
                new BigDecimal("4200.00")
        );
    }
}
