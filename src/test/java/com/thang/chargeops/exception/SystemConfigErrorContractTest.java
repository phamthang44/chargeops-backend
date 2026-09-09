package com.thang.chargeops.exception;

import com.thang.chargeops.booking.config.BookingConfigValueValidator;
import com.thang.chargeops.common.entity.SystemConfig;
import com.thang.chargeops.common.enums.ValueType;
import com.thang.chargeops.common.exception.SystemConfigException;
import com.thang.chargeops.common.repository.SystemConfigRepository;
import com.thang.chargeops.common.service.SystemConfigService;
import com.thang.chargeops.exception.errorcode.SystemConfigErrorCode;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SystemConfigErrorContractTest {
    @RestController
    static class ErrorProbe {
        @GetMapping("/test/config-errors/{code}")
        public void fail(@PathVariable String code) {
            throw new SystemConfigException(SystemConfigErrorCode.valueOf(code),
                    new RuntimeException("raw-secret-config-value"));
        }
    }

    @ParameterizedTest
    @EnumSource(SystemConfigErrorCode.class)
    void existingHandlerReturnsStableLocalizedContract(SystemConfigErrorCode code) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ErrorProbe())
                .setControllerAdvice(new GlobalHandlerError()).build();
        mvc.perform(get("/test/config-errors/" + code.name()))
                .andExpect(status().is(code.getHttpStatus().value()))
                .andExpect(jsonPath("$.error.code").value(code.getCode()))
                .andExpect(jsonPath("$.error.messageKey").value(code.getMessageKey()))
                .andExpect(jsonPath("$.error.message").value(code.getMessage()))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("raw-secret-config-value"))));
        assertThat(ErrorMessage.defaultMessage(code.getMessageKey())).isEqualTo(code.getMessage());
    }

    @Test
    void codesAndTranslationKeysAreUnique() {
        assertThat(Arrays.stream(SystemConfigErrorCode.values()).map(SystemConfigErrorCode::getCode)).doesNotHaveDuplicates();
        assertThat(Arrays.stream(SystemConfigErrorCode.values()).map(SystemConfigErrorCode::getMessageKey)).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @CsvSource({
            "booking.payment_hold_minutes,NUMBER,0,OUT_OF_RANGE",
            "booking.operating_grid_minutes,NUMBER,abc,INVALID_FORMAT",
            "booking.unknown,NUMBER,10,UNSUPPORTED_KEY",
            "custom.number,NUMBER,abc,INVALID_FORMAT",
            "custom.flag,BOOLEAN,yes,INVALID_FORMAT"
    })
    void invalidWritesExposeSpecificCodeAndDoNotSave(String key, ValueType type, String value, SystemConfigErrorCode expected) {
        var repository = mock(SystemConfigRepository.class);
        var service = new SystemConfigService(repository, List.of(new BookingConfigValueValidator()));
        var row = SystemConfig.builder().configKey(key).configValue("10").valueType(type).build();
        when(repository.findByConfigKey(key)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.updateConfig(key, value, UUID.randomUUID()))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(repository, never()).save(any());
        assertThat(row.getConfigValue()).isEqualTo("10");
    }

    @Test
    void absentInputAndMissingStoredMetadataAreDifferentErrors() {
        var repository = mock(SystemConfigRepository.class);
        var service = new SystemConfigService(repository, List.of(new BookingConfigValueValidator()));
        assertThatThrownBy(() -> service.updateConfig(null, "10", UUID.randomUUID()))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SystemConfigErrorCode.KEY_REQUIRED));
        assertThatThrownBy(() -> service.updateConfig("custom.key", " ", UUID.randomUUID()))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SystemConfigErrorCode.VALUE_REQUIRED));
        verifyNoInteractions(repository);
        when(repository.findByConfigKey("custom.key")).thenReturn(Optional.of(SystemConfig.builder().configValue("10").build()));
        assertThatThrownBy(() -> service.updateConfig("custom.key", "20", UUID.randomUUID()))
                .isInstanceOfSatisfying(SystemConfigException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SystemConfigErrorCode.STORED_INVALID));
        verify(repository, never()).save(any());
    }
}
