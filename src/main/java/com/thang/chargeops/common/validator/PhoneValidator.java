package com.thang.chargeops.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;


public class PhoneValidator implements ConstraintValidator<PhoneNumber, String> {


    @Override
    public void initialize(PhoneNumber phoneNumberNo) {
    }

    @Override
    public boolean isValid(String phoneNo, ConstraintValidatorContext cxt) {
        if (phoneNo == null || phoneNo.trim().isEmpty()) {
            return true;
        }

        // Normalize: remove non-numeric characters
        String cleanPhone = phoneNo.replaceAll("[^0-9]", "");
        
        // Handle 84 prefix
        if (cleanPhone.startsWith("84")) {
            cleanPhone = "0" + cleanPhone.substring(2);
        }

        // Validate normalized format: 10 digits, starts with 0, valid prefix
        return cleanPhone.matches("^(0)(3|5|7|8|9)[0-9]{8}$");
    }

}
