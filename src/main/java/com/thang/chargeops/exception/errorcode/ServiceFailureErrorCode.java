package com.thang.chargeops.exception.errorcode;

import org.springframework.http.HttpStatus;

/** Policy conflicts for Owner station-failure commands; never executor outcomes. */
public enum ServiceFailureErrorCode implements BaseErrorCode {
    VERSION_CONFLICT("VERSION_CONFLICT", "Booking changed. Refresh and confirm again."),
    DECISION_CHANGED("DECISION_CHANGED", "Financial decisions changed. Refresh and confirm again."),
    OPERATIONAL_RESOLUTION_REQUIRED("OPERATIONAL_RESOLUTION_REQUIRED", "End the actual session through incident handling first."),
    USE_OWNER_CANCEL_COMMAND("USE_OWNER_CANCEL_COMMAND", "Use Owner cancellation before the check-in deadline."),
    RECONCILIATION_REQUIRED("RECONCILIATION_REQUIRED", "Payment and receipt facts require reconciliation."),
    NO_APPLIED_PAYMENT("NO_APPLIED_PAYMENT", "No accepted package payment is available for refund."),
    OUT_OF_DEMO_SCOPE("OUT_OF_DEMO_SCOPE", "Only Simulator payments are supported."),
    RESPONSIBILITY_ALREADY_ACCEPTED("RESPONSIBILITY_ALREADY_ACCEPTED", "Owner responsibility was already recorded.");

    private final String semantic;
    private final String message;
    ServiceFailureErrorCode(String semantic, String message) { this.semantic = semantic; this.message = message; }
    @Override public HttpStatus getHttpStatus() { return HttpStatus.CONFLICT; }
    @Override public String getCode() { return "BKG_" + semantic; }
    @Override public String getMessageKey() { return "error.booking.serviceFailure." + semantic; }
    @Override public String getMessage() { return message; }
}
