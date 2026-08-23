package com.thang.chargeops.station.exception;

import com.thang.chargeops.station.exception.violation.ConnectorViolation;

public class ConnectorDomainException extends RuntimeException {

    private final ConnectorViolation violation;

    public ConnectorDomainException(ConnectorViolation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public ConnectorViolation getViolation() {
        return violation;
    }
}
