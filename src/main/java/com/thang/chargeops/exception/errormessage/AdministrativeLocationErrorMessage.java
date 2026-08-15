package com.thang.chargeops.exception.errormessage;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class AdministrativeLocationErrorMessage {

    public static final ErrorMessage.Template PROVINCE_NOT_FOUND =
            template("error.location.provinceNotFound", "Province not found: {0}");
    public static final ErrorMessage.Template WARD_NOT_FOUND =
            template("error.location.wardNotFound", "Ward not found: {0}");
    public static final ErrorMessage.Template WARD_PROVINCE_MISMATCH =
            template(
                    "error.location.wardProvinceMismatch",
                    "Ward {0} does not belong to province {1}"
            );

    private AdministrativeLocationErrorMessage() {
    }
}
