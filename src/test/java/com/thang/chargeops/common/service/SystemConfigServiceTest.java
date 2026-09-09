package com.thang.chargeops.common.service;

import com.thang.chargeops.common.entity.SystemConfig;
import com.thang.chargeops.common.enums.ValueType;
import com.thang.chargeops.common.repository.SystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import com.thang.chargeops.common.exception.SystemConfigException;
import static com.thang.chargeops.exception.errorcode.SystemConfigErrorCode.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceTest {

    @Mock
    private SystemConfigRepository repository;

    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(repository, java.util.List.of(new com.thang.chargeops.booking.config.BookingConfigValueValidator()));
    }

    @Test
    void getRequiredString_returnsValueWhenPresent() {
        when(repository.findByConfigKey("test.key")).thenReturn(Optional.of(
                SystemConfig.builder()
                        .configKey("test.key")
                        .configValue("valid-value")
                        .valueType(ValueType.STRING)
                        .build()
        ));

        String value = service.getRequiredString("test.key");
        assertThat(value).isEqualTo("valid-value");
    }

    @Test
    void getRequiredString_throwsWhenKeyMissing() {
        when(repository.findByConfigKey("missing.key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRequiredString("missing.key"))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(REQUIRED_MISSING));
    }

    @Test
    void getRequiredString_throwsWhenValueIsBlank() {
        when(repository.findByConfigKey("blank.key")).thenReturn(Optional.of(
                SystemConfig.builder()
                        .configKey("blank.key")
                        .configValue("   ")
                        .valueType(ValueType.STRING)
                        .build()
        ));

        assertThatThrownBy(() -> service.getRequiredString("blank.key"))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(REQUIRED_MISSING));
    }

    @Test
    void getRequiredInt_parsesAndValidatesSuccessfully() {
        when(repository.findByConfigKey("booking.cancellation_grace_minutes")).thenReturn(Optional.of(
                SystemConfig.builder()
                        .configKey("booking.cancellation_grace_minutes")
                        .configValue(" 10 ")
                        .valueType(ValueType.NUMBER)
                        .build()
        ));

        int val = service.getRequiredInt("booking.cancellation_grace_minutes", v -> v >= 0, "Cannot be negative");
        assertThat(val).isEqualTo(10);
    }

    @Test
    void getRequiredInt_throwsWhenFormatIsInvalid() {
        when(repository.findByConfigKey("booking.cancellation_grace_minutes")).thenReturn(Optional.of(
                SystemConfig.builder()
                        .configKey("booking.cancellation_grace_minutes")
                        .configValue("10 phut")
                        .valueType(ValueType.NUMBER)
                        .build()
        ));

        assertThatThrownBy(() -> service.getRequiredInt("booking.cancellation_grace_minutes", v -> v >= 0, "Cannot be negative"))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(STORED_INVALID));
    }

    @Test
    void getRequiredInt_throwsWhenValidatorFails() {
        when(repository.findByConfigKey("booking.payment_hold_minutes")).thenReturn(Optional.of(
                SystemConfig.builder()
                        .configKey("booking.payment_hold_minutes")
                        .configValue("0")
                        .valueType(ValueType.NUMBER)
                        .build()
        ));

        assertThatThrownBy(() -> service.getRequiredInt("booking.payment_hold_minutes", v -> v >= 1, "Must be >= 1"))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(STORED_INVALID));
    }

    @Test
    void updateConfig_updatesAndSavesSuccessfully() {
        SystemConfig existing = SystemConfig.builder()
                .configKey("booking.cancellation_grace_minutes")
                .configValue("10")
                .valueType(ValueType.NUMBER)
                .build();
        when(repository.findByConfigKey("booking.cancellation_grace_minutes")).thenReturn(Optional.of(existing));

        UUID adminId = UUID.randomUUID();
        service.updateConfig("booking.cancellation_grace_minutes", "15", adminId);

        assertThat(existing.getConfigValue()).isEqualTo("15");
        assertThat(existing.getUpdatedBy()).isEqualTo(adminId);
        verify(repository).save(existing);
    }

    @Test
    void updateConfig_throwsWhenKeyNotFound() {
        when(repository.findByConfigKey("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateConfig("unknown", "15", UUID.randomUUID()))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(NOT_FOUND));
    }
}
