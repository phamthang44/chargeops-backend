package com.thang.chargeops.station.dto.station.validation;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class StationDiscoveryLocationValidator
        implements ConstraintValidator<ValidStationDiscoveryLocation, StationDiscoveryFilter> {

    @Override
    public boolean isValid(
            StationDiscoveryFilter filter,
            ConstraintValidatorContext context
    ) {
        if (filter == null) {
            return true;
        }

        boolean latitudeRequired = filter.getLongitude() != null
                || filter.getMaxDistanceKm() != null;
        boolean longitudeRequired = filter.getLatitude() != null
                || filter.getMaxDistanceKm() != null;

        boolean latitudeMissing = latitudeRequired && filter.getLatitude() == null;
        boolean longitudeMissing = longitudeRequired && filter.getLongitude() == null;

        if (!latitudeMissing && !longitudeMissing) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        if (latitudeMissing) {
            addFieldViolation(
                    context,
                    "latitude",
                    StationErrorMessage.STATION_LATITUDE_REQUIRED_KEY
            );
        }
        if (longitudeMissing) {
            addFieldViolation(
                    context,
                    "longitude",
                    StationErrorMessage.STATION_LONGITUDE_REQUIRED_KEY
            );
        }
        return false;
    }

    /**
     * VI: Gắn lỗi của constraint cấp class vào đúng field để frontend hiển thị đúng chỗ.
     * EN: Attaches a class-level constraint failure to the exact field used by the frontend.
     */
    private void addFieldViolation(
            ConstraintValidatorContext context,
            String field,
            String message
    ) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
