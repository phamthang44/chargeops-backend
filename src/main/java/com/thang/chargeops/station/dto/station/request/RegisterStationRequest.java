package com.thang.chargeops.station.dto.station.request;


import com.thang.chargeops.common.validator.PhoneNumber;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record RegisterStationRequest(
    @NotBlank(message = StationErrorMessage.STATION_NAME_REQUIRED_KEY)
    @Size(max = 100, message = StationErrorMessage.STATION_NAME_MAX_LENGTH_KEY)
    String name,

    @NotBlank(message = StationErrorMessage.STATION_ADDRESS_REQUIRED_KEY)
    @Size(max = 200, message = StationErrorMessage.STATION_ADDRESS_MAX_LENGTH_KEY)
    String addressLine,

    @Size(max = 500, message = StationErrorMessage.STATION_DESCRIPTION_MAX_LENGTH_KEY)
    String description, //optional ?

    @NotBlank(message = StationErrorMessage.STATION_PROVINCE_CODE_REQUIRED_KEY)
    @Pattern(regexp = "\\d*", message = StationErrorMessage.STATION_PROVINCE_CODE_INVALID_KEY)
    String provinceCode,

    @NotBlank(message = StationErrorMessage.STATION_WARD_CODE_REQUIRED_KEY)
    @Pattern(regexp = "\\d*", message = StationErrorMessage.STATION_WARD_CODE_INVALID_KEY)
    String wardCode,

    @NotNull(message = StationErrorMessage.STATION_LATITUDE_REQUIRED_KEY)
    @DecimalMin(value = "-90.0", message = StationErrorMessage.STATION_LATITUDE_INVALID_KEY)
    @DecimalMax(value = "90.0", message = StationErrorMessage.STATION_LATITUDE_INVALID_KEY)
    BigDecimal latitude,

    @NotNull(message = StationErrorMessage.STATION_LONGITUDE_REQUIRED_KEY)
    @DecimalMin(value = "-180.0", message = StationErrorMessage.STATION_LONGITUDE_INVALID_KEY)
    @DecimalMax(value = "180.0", message = StationErrorMessage.STATION_LONGITUDE_INVALID_KEY)
    BigDecimal longitude,

    @PhoneNumber(message = StationErrorMessage.STATION_CONTACT_PHONE_INVALID_KEY)
    @Size(max = 20, message = StationErrorMessage.STATION_CONTACT_PHONE_MAX_LENGTH_KEY)
    @NotBlank(message = StationErrorMessage.STATION_CONTACT_PHONE_REQUIRED_KEY)
    String contactPhone,

    @Min(value = 1, message =  StationErrorMessage.STATION_PLANNED_CHARGE_POINT_COUNT_KEY)
    int plannedChargePointCount  //chỉ số dự kiến ko phải chính thức ko dùng thay thế cho Charge Point thật

) {
}
