package com.thang.chargeops.station.dto.station.validation;

import com.thang.chargeops.exception.errormessage.ValidationErrorMessage;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StationDiscoveryLocationValidator.class)
public @interface ValidStationDiscoveryLocation {

    String message() default ValidationErrorMessage.INVALID_PARAMETERS_KEY;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
