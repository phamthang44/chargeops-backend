package com.thang.chargeops.station.exception;

import com.thang.chargeops.station.exception.violation.LicenseViolation;

public class LicenseDomainException extends RuntimeException {

    private final LicenseViolation violation;

    public LicenseDomainException(LicenseViolation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public LicenseViolation getViolation() {
        return violation;
    }
}
