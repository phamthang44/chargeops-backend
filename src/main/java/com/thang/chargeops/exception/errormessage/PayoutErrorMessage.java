package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class PayoutErrorMessage {
    private PayoutErrorMessage() {}

    public static final ErrorMessage.Template NOT_FOUND = template("error.payout.notFound", "Payout was not found");
    public static final ErrorMessage.Template CODE_CONFLICT = template("error.payout.codeConflict", "Payout code already exists");
    public static final ErrorMessage.Template STATE_CONFLICT = template("error.payout.stateConflict", "Payout state does not allow this action");
    public static final ErrorMessage.Template BOOKING_NOT_ELIGIBLE = template("error.payout.bookingNotEligible", "Booking is not eligible for payout");
    public static final ErrorMessage.Template BOOKING_ALREADY_RESERVED = template("error.payout.bookingAlreadyReserved", "Booking is already held by a payout");
    public static final ErrorMessage.Template ENVIRONMENT_MISMATCH = template("error.payout.environmentMismatch", "Payout financial environment does not match its source");
    public static final ErrorMessage.Template VERSION_CONFLICT = template("error.payout.versionConflict", "Payout data changed; refresh before continuing");
    public static final ErrorMessage.Template REQUEST_CONFLICT = template("error.payout.requestConflict", "Payout request key was reused with different data");
    public static final ErrorMessage.Template CANNOT_CANCEL = template("error.payout.cannotCancel", "Payout cannot be cancelled in its current state");

    static List<ErrorMessage.Template> templates() {
        return List.of(NOT_FOUND, CODE_CONFLICT, STATE_CONFLICT, BOOKING_NOT_ELIGIBLE,
                BOOKING_ALREADY_RESERVED, ENVIRONMENT_MISMATCH, VERSION_CONFLICT,
                REQUEST_CONFLICT, CANNOT_CANCEL);
    }
}
