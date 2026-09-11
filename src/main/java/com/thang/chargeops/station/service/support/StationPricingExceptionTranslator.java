package com.thang.chargeops.station.service.support;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import org.springframework.stereotype.Component;

@Component
public class StationPricingExceptionTranslator {

    public AppException translate(StationPricingDomainException exception) {
        StationErrorCode errorCode = switch (exception.getViolation()) {
            case MIN_BOOKING_DURATION_INVALID ->
                    StationErrorCode.PRICING_MIN_BOOKING_DURATION_INVALID;
            case MIN_BOOKING_DURATION_90_NOT_SUPPORTED ->
                    StationErrorCode.PRICING_MIN_BOOKING_DURATION_90_NOT_SUPPORTED;
            case BASE_PRICE_INVALID -> StationErrorCode.PRICING_BASE_PRICE_INVALID;
            case OPERATING_WEEK_INVALID -> StationErrorCode.PRICING_OPERATING_WEEK_INVALID;
            case OPEN_24_HOURS_PERIOD_NOT_ALLOWED ->
                    StationErrorCode.PRICING_OPEN_24_HOURS_PERIOD_NOT_ALLOWED;
            case CLOSED_DAY_TIME_PRESENT -> StationErrorCode.PRICING_CLOSED_DAY_TIME_PRESENT;
            case OPEN_DAY_TIME_REQUIRED -> StationErrorCode.PRICING_OPEN_DAY_TIME_REQUIRED;
            case OPERATING_WINDOW_AMBIGUOUS ->
                    StationErrorCode.PRICING_OPERATING_WINDOW_AMBIGUOUS;
            case TOU_RULES_REQUIRED -> StationErrorCode.PRICING_TOU_RULES_REQUIRED;
            case TOU_NAME_REQUIRED -> StationErrorCode.PRICING_TOU_NAME_REQUIRED;
            case TOU_NAME_DUPLICATED -> StationErrorCode.PRICING_TOU_NAME_DUPLICATED;
            case TOU_WINDOW_INVALID -> StationErrorCode.PRICING_TOU_WINDOW_INVALID;
            case TOU_RATE_INVALID -> StationErrorCode.PRICING_TOU_RATE_INVALID;
            case TOU_RULES_OVERLAP -> StationErrorCode.PRICING_TOU_RULES_OVERLAP;
            case CONFIGURATION_CONFLICT -> StationErrorCode.PRICING_CONFIGURATION_CONFLICT;
        };
        return new AppException(errorCode);
    }
}
