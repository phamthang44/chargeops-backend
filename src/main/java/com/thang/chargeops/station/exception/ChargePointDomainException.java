package com.thang.chargeops.station.exception;

import com.thang.chargeops.station.exception.violation.ChargePointViolation;

public class ChargePointDomainException extends RuntimeException {

    private final ChargePointViolation violation;

    public ChargePointDomainException(ChargePointViolation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public ChargePointViolation getViolation() {
        return violation;
    }
}
