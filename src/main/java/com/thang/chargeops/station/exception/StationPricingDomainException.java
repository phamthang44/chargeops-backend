package com.thang.chargeops.station.exception;

import com.thang.chargeops.station.exception.violation.StationPricingViolation;

public class StationPricingDomainException extends RuntimeException {

    private final StationPricingViolation violation;

    public StationPricingDomainException(StationPricingViolation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public StationPricingViolation getViolation() {
        return violation;
    }
}
