package com.thang.chargeops.booking.config;

import com.thang.chargeops.common.service.SystemConfigValueValidator;
import com.thang.chargeops.common.exception.SystemConfigException;
import static com.thang.chargeops.exception.errorcode.SystemConfigErrorCode.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.IntPredicate;

/** The same bounds apply before saving and when reading persisted Booking config. */
@Component
public class BookingConfigValueValidator implements SystemConfigValueValidator {
    private static final Map<String, IntPredicate> RULES = Map.of(
            BookingPolicyConfig.KEY_CANCELLATION_GRACE, v -> v >= 0,
            BookingPolicyConfig.KEY_PAYMENT_HOLD, v -> v >= 1,
            BookingPolicyConfig.KEY_MINIMUM_ADVANCE, v -> v >= 0,
            BookingPolicyConfig.KEY_OPERATING_GRID, v -> v == 15 || v == 30 || v == 60,
            BookingPolicyConfig.KEY_ADVANCE_BOOKING_DAYS, v -> v >= 1 && v <= 2,
            BookingPolicyConfig.KEY_CHECKIN_CUTOFF_BEFORE_END, v -> v >= 0 && v < 30
    );

    @Override
    public boolean supports(String key) {
        return key.startsWith("booking.");
    }

    @Override
    public void validate(String key, String value) {
        IntPredicate rule = RULES.get(key);
        if (rule == null) {
            throw new SystemConfigException(UNSUPPORTED_KEY);
        }
        try {
            if (!rule.test(Integer.parseInt(value.trim()))) {
                throw new SystemConfigException(OUT_OF_RANGE);
            }
        } catch (NumberFormatException e) {
            throw new SystemConfigException(INVALID_FORMAT, e);
        }
    }
}
