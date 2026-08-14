package com.thang.chargeops.station.dto.license.request;


import com.thang.chargeops.common.validator.PhoneNumber;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterStationRequest(
    @NotBlank(message = StationErrorMessage.STATION_NAME_REQUIRED_KEY)
    String name,

    @NotBlank(message = StationErrorMessage.STATION_ADDRESS_REQUIRED_KEY)
    String address,

    String description, //optional ?

    @NotBlank(message = StationErrorMessage.STATION_CITY_REQUIRED_KEY)
    String city,

    @NotBlank(message = StationErrorMessage.STATION_LATITUDE_REQUIRED_KEY)
    String latitude,

    @NotBlank(message = StationErrorMessage.STATION_LONGITUDE_REQUIRED_KEY)
    String longitude,

    @PhoneNumber
    @NotBlank(message = StationErrorMessage.STATION_CONTACT_PHONE_REQUIRED_KEY)
    String contactPhone,

    @Min(value = 1, message =  StationErrorMessage.STATION_PLANNED_CHARGE_POINT_COUNT_KEY)
    int plannedChargePointCount  //chỉ số dự kiến ko phải chính thức ko dùng thay thế cho Charge Point thật

) {
}
