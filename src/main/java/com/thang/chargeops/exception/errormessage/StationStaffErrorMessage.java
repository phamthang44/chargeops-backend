package com.thang.chargeops.exception.errormessage;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

/**
 * Default backend messages for the station-staff assignment domain.
 *
 * <p>The frontend should localize by {@code messageKey}; these English messages are safe
 * fallbacks for logs and non-UI clients.</p>
 */
public final class StationStaffErrorMessage {

    public static final ErrorMessage.Template CANDIDATE_NOT_FOUND =
            template("error.stationStaff.candidateNotFound", "Staff candidate was not found");
    public static final ErrorMessage.Template STATION_NOT_ACTIVE_FOR_ASSIGNMENT =
            template(
                    "error.stationStaff.stationNotActiveForAssignment",
                    "Staff can only be assigned to an active station"
            );
    public static final ErrorMessage.Template SELF_ASSIGNMENT_NOT_ALLOWED =
            template(
                    "error.stationStaff.selfAssignmentNotAllowed",
                    "A station owner cannot be assigned as staff"
            );
    public static final ErrorMessage.Template CANDIDATE_ACCOUNT_INACTIVE =
            template(
                    "error.stationStaff.candidateAccountInactive",
                    "The staff candidate account is not active"
            );
    public static final ErrorMessage.Template CANDIDATE_ROLE_NOT_ALLOWED =
            template(
                    "error.stationStaff.candidateRoleNotAllowed",
                    "The account role is not eligible for a station staff assignment"
            );
    public static final ErrorMessage.Template ACTIVE_ASSIGNMENT_ALREADY_EXISTS =
            template(
                    "error.stationStaff.activeAssignmentAlreadyExists",
                    "This account already has an active station staff assignment"
            );
    public static final ErrorMessage.Template ASSIGNMENT_NOT_FOUND =
            template("error.stationStaff.assignmentNotFound", "Station staff assignment was not found");
    public static final ErrorMessage.Template ASSIGNMENT_ALREADY_REVOKED =
            template(
                    "error.stationStaff.assignmentAlreadyRevoked",
                    "Station staff assignment has already been revoked"
            );
    public static final ErrorMessage.Template IDENTITY_PROVIDER_UNAVAILABLE =
            template(
                    "error.stationStaff.identityProviderUnavailable",
                    "The identity provider is temporarily unavailable"
            );
    public static final ErrorMessage.Template IDENTITY_ROLE_OPERATION_FAILED =
            template(
                    "error.stationStaff.identityRoleOperationFailed",
                    "The identity provider rejected the role operation"
            );
    private StationStaffErrorMessage() {
    }
}
