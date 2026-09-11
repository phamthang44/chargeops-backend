package com.thang.chargeops.booking.config;

import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.common.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BookingPolicyConfigTest {

    @Mock
    private SystemConfigService configService;

    private BookingPolicyConfig config;

    @BeforeEach
    void setUp() {
        config = new BookingPolicyConfig(configService);
    }

    private void mockStandardConfigs(int grace) {
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_CANCELLATION_GRACE), any(), any())).thenReturn(grace);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_PAYMENT_HOLD), any(), any())).thenReturn(10);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_MINIMUM_ADVANCE), any(), any())).thenReturn(60);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_OPERATING_GRID), any(), any())).thenReturn(30);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_ADVANCE_BOOKING_DAYS), any(), any())).thenReturn(2);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_CHECKIN_CUTOFF_BEFORE_END), any(), any())).thenReturn(15);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_DURATION_MIN), any(), any())).thenReturn(30);
        when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_DURATION_STEP), any(), any())).thenReturn(30);
        lenient().when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_DURATION_MAX), any(), any())).thenReturn(180);
        lenient().when(configService.getRequiredInt(eq(BookingPolicyConfig.KEY_MAX_PENDING_PER_DRIVER), any(), any())).thenReturn(1);
    }

    @Test
    void getCancellationSummary_buildsCorrectSummaryFromConfig() {
        mockStandardConfigs(15);

        CancellationPolicySummary summary = config.getCancellationSummary();

        assertThat(summary.policyVersion()).startsWith("booking-v4.9-");
        assertThat(summary.gracePeriodMinutes()).isEqualTo(15);
        assertThat(summary.withinGraceRefundPercent()).isEqualTo(100);
        assertThat(summary.afterGraceRefundPercent()).isZero();
        assertThat(summary.verifiedStationFailureRefundPercent()).isEqualTo(100);
        assertThat(summary.stationFailureRequiresVerification()).isTrue();
    }

    @Test
    void toPolicyResponse_matchesContractAndFixtures() {
        mockStandardConfigs(10);

        BookingPolicyResponse response = config.toPolicyResponse();

        assertThat(response.policyVersion()).isEqualTo("booking-v4.9");
        assertThat(response.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(response.advanceMinMinutes()).isEqualTo(60);
        assertThat(response.selectableStartDayOffsets()).isEqualTo(List.of(0, 1));
        assertThat(response.startStepMin()).isEqualTo(30);
        assertThat(response.minDurationMin()).isEqualTo(30);
        assertThat(response.durationStepMin()).isEqualTo(30);
        assertThat(response.paymentHoldMin()).isEqualTo(10);
        assertThat(response.cancellationGraceMin()).isEqualTo(10);
        assertThat(response.checkInCloseBeforeEndMin()).isEqualTo(15);
        assertThat(response.voluntaryRefundAfterGracePercent()).isZero();
        assertThat(response.verifiedStationFailureRefundPercent()).isEqualTo(100);
        assertThat(response.bookingFee()).isZero();
        assertThat(response.commissionPercent()).isZero();
        assertThat(response.supportedPaymentMethods()).containsExactly("SIMULATOR");
        assertThat(response.summary()).contains("10 phút");
    }

    @Test
    void defaults_providesSensibleDefaultsWithoutDatabase() {
        BookingPolicyConfig defaults = BookingPolicyConfig.defaults();

        assertThat(defaults.getCancellationGraceMinutes()).isEqualTo(10);
        assertThat(defaults.getPaymentHoldMinutes()).isEqualTo(10);
        assertThat(defaults.getMinimumAdvanceMinutes()).isEqualTo(60);
        assertThat(defaults.getOperatingGridMinutes()).isEqualTo(30);
        assertThat(defaults.getAdvanceBookingDays()).isEqualTo(2);
        assertThat(defaults.getCheckInCutoffBeforeEndMinutes()).isEqualTo(15);
        assertThat(defaults.getMinDurationMinutes()).isEqualTo(30);
        assertThat(defaults.getDurationStepMinutes()).isEqualTo(30);
        assertThat(defaults.getMaxDurationMinutes()).isEqualTo(180);
        assertThat(defaults.getMaxPendingPerDriver()).isEqualTo(1);
        assertThat(defaults.getCancellationSummary().gracePeriodMinutes()).isEqualTo(10);
    }
}
