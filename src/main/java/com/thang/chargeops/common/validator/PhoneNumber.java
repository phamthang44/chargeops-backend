package com.thang.chargeops.common.validator;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PhoneValidator.class)
@Target( { ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface PhoneNumber {
    String message() default ErrorMessage.Validation.PHONE_INVALID_KEY;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
