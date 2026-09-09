package com.thang.chargeops.common.service;

import com.thang.chargeops.common.entity.SystemConfig;
import com.thang.chargeops.common.exception.SystemConfigException;
import static com.thang.chargeops.exception.errorcode.SystemConfigErrorCode.*;
import com.thang.chargeops.common.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Service đọc và quản lý cấu hình runtime (system_configs).
 * Tuân thủ nguyên tắc Fail-Fast: nếu thiếu cấu hình hoặc giá trị không hợp lệ,
 * ném ngoại lệ rõ ràng để bảo vệ tính toàn vẹn nghiệp vụ (không âm thầm nuốt lỗi).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigService {

    private final SystemConfigRepository systemConfigRepository;
    private final List<SystemConfigValueValidator> validators;

    /**
     * Đọc giá trị thô từ database.
     */
    @Transactional(readOnly = true)
    public Optional<String> getRawValue(String key) {
        requireKey(key);
        return systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue);
    }

    /**
     * Đọc giá trị chuỗi bắt buộc. Ném SystemConfigException nếu không tồn tại hoặc rỗng.
     */
    @Transactional(readOnly = true)
    public String getRequiredString(String key) {
        return getRawValue(key)
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new SystemConfigException(REQUIRED_MISSING));
    }

    /**
     * Đọc giá trị số nguyên bắt buộc với bộ kiểm tra (validator) cụ thể.
     * Ném SystemConfigException nếu thiếu, sai định dạng hoặc vi phạm ràng buộc miền giá trị.
     */
    @Transactional(readOnly = true)
    public int getRequiredInt(String key, IntPredicate validator, String validationErrorMessage) {
        String raw = getRequiredString(key);
        try {
            validateDomainValue(key, raw);
        } catch (SystemConfigException e) {
            log.error("Invalid stored system configuration: key={}, reason={}", key, e.getErrorCodeStr());
            throw new SystemConfigException(STORED_INVALID, e);
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new SystemConfigException(STORED_INVALID, e);
        }

        if (validator != null && !validator.test(parsed)) {
            throw new SystemConfigException(STORED_INVALID);
        }
        return parsed;
    }

    /**
     * Cập nhật giá trị cấu hình (dành cho Admin operations).
     */
    @Transactional
    public void updateConfig(String key, String newValue, UUID updatedBy) {
        requireKey(key);
        if (newValue == null || newValue.isBlank()) {
            throw new SystemConfigException(VALUE_REQUIRED);
        }

        SystemConfig config = systemConfigRepository.findByConfigKey(key)
                .orElseThrow(() -> new SystemConfigException(NOT_FOUND));

        String normalized = newValue.trim();
        validateDomainValue(key, normalized);
        if (config.getValueType() == null) {
            throw new SystemConfigException(STORED_INVALID);
        }
        switch (config.getValueType()) {
            case NUMBER -> {
                try {
                    new BigDecimal(normalized);
                } catch (NumberFormatException e) {
                    throw new SystemConfigException(INVALID_FORMAT, e);
                }
            }
            case BOOLEAN -> {
                if (!normalized.equals("true") && !normalized.equals("false")) {
                    throw new SystemConfigException(INVALID_FORMAT);
                }
            }
            case STRING -> { }
        }
        config.setConfigValue(normalized);
        config.setUpdatedBy(updatedBy);
        systemConfigRepository.save(config);
        log.info("System configuration updated: key={}, actor={}", key, updatedBy);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new SystemConfigException(KEY_REQUIRED);
        }
    }

    private void validateDomainValue(String key, String value) {
        validators.stream().filter(v -> v.supports(key)).forEach(v -> v.validate(key, value));
    }
}
