package com.thang.chargeops.booking.config;

import com.thang.chargeops.booking.policy.impl.PlatformBookingCancellationPolicy;
import com.thang.chargeops.common.entity.SystemConfig;
import com.thang.chargeops.common.enums.ValueType;
import com.thang.chargeops.common.repository.SystemConfigRepository;
import com.thang.chargeops.common.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.HashMap;
import com.thang.chargeops.common.exception.SystemConfigException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BookingPolicyWiringTest {
    private final Map<String, SystemConfig> rows = new HashMap<>();
    private final SystemConfigRepository repository = mock(SystemConfigRepository.class);
    private final SystemConfigService service = new SystemConfigService(repository,
            List.of(new BookingConfigValueValidator()));

    BookingPolicyWiringTest() {
        seed(BookingPolicyConfig.KEY_CANCELLATION_GRACE, "10");
        seed(BookingPolicyConfig.KEY_PAYMENT_HOLD, "10");
        seed(BookingPolicyConfig.KEY_MINIMUM_ADVANCE, "60");
        seed(BookingPolicyConfig.KEY_OPERATING_GRID, "30");
        seed(BookingPolicyConfig.KEY_ADVANCE_BOOKING_DAYS, "2");
        seed(BookingPolicyConfig.KEY_CHECKIN_CUTOFF_BEFORE_END, "15");
        when(repository.findByConfigKey(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
    }

    private void seed(String key, String value) {
        rows.put(key, SystemConfig.builder().configKey(key).configValue(value).valueType(ValueType.NUMBER).build());
    }

    @Test
    void springWiresLiveConfigIntoDiscoveryAndPolicyEndpointSource() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SystemConfigService.class, () -> service);
            context.register(BookingPolicyConfig.class, PlatformBookingCancellationPolicy.class);
            context.refresh();
            var config = context.getBean(BookingPolicyConfig.class);
            var cancellation = context.getBean(PlatformBookingCancellationPolicy.class);
            String originalVersion = config.toPolicyResponse().policyVersion();

            service.updateConfig(BookingPolicyConfig.KEY_CANCELLATION_GRACE, "5", UUID.randomUUID());

            assertThat(config.toPolicyResponse().cancellationGraceMin()).isEqualTo(5);
            assertThat(cancellation.getSummary().gracePeriodMinutes()).isEqualTo(5);
            assertThat(cancellation.getSummary().policyVersion())
                    .isEqualTo(config.toPolicyResponse().policyVersion()).isNotEqualTo(originalVersion);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "booking.payment_hold_minutes,0", "booking.payment_hold_minutes,-1",
            "booking.cancellation_grace_minutes,-5", "booking.cancellation_grace_minutes,abc",
            "booking.operating_grid_minutes,20", "booking.operating_grid_minutes,abc",
            "booking.advance_booking_days,0", "booking.advance_booking_days,2147483647",
            "booking.checkin_cutoff_before_end_minutes,30", "booking.minimum_advance_minutes,-1"
    })
    void rejectsInvalidWriteWithoutChangingPersistedValue(String key, String value) {
        String original = rows.get(key).getConfigValue();
        assertThatThrownBy(() -> service.updateConfig(key, value, UUID.randomUUID()))
                .isInstanceOf(SystemConfigException.class);
        assertThat(rows.get(key).getConfigValue()).isEqualTo(original);
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "booking.cancellation_grace_minutes,5", "booking.payment_hold_minutes,11",
            "booking.minimum_advance_minutes,90", "booking.operating_grid_minutes,15",
            "booking.advance_booking_days,1", "booking.checkin_cutoff_before_end_minutes,10"
    })
    void eachPolicySettingChangesVersionAndRestoringValuesRestoresVersion(String key, String value) {
        var config = new BookingPolicyConfig(service);
        String initial = config.toPolicyResponse().policyVersion();
        String original = rows.get(key).getConfigValue();
        service.updateConfig(key, value, UUID.randomUUID());
        var updated = config.toPolicyResponse();
        assertThat(updated.policyVersion()).isNotEqualTo(initial)
                .isEqualTo(config.toPolicyResponse().policyVersion());
        assertThat(updated.minDurationMin()).isEqualTo(30);
        assertThat(updated.durationStepMin()).isEqualTo(30);
        service.updateConfig(key, original, UUID.randomUUID());
        assertThat(config.toPolicyResponse().policyVersion()).isEqualTo(initial);
    }

    @Test
    void corruptOrMissingStoredPolicyFailsInsteadOfFallingBack() {
        var config = new BookingPolicyConfig(service);
        rows.get(BookingPolicyConfig.KEY_OPERATING_GRID).setConfigValue("20");
        assertThatThrownBy(config::toPolicyResponse).isInstanceOf(SystemConfigException.class);
        rows.remove(BookingPolicyConfig.KEY_CANCELLATION_GRACE);
        assertThatThrownBy(config::getCancellationSummary).isInstanceOf(SystemConfigException.class);
    }
}
