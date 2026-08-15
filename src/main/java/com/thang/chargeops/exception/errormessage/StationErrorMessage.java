package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class StationErrorMessage {

    private StationErrorMessage() {
        /* This utility class should not be instantiated */
    }


    public static final String STATION_NAME_REQUIRED_KEY = "validation.station.name.required";
    public static final String STATION_NAME_MAX_LENGTH_KEY = "validation.station.name.maxLength";
    public static final String STATION_DESCRIPTION_MAX_LENGTH_KEY = "validation.station.description.maxLength";
    public static final String STATION_ADDRESS_REQUIRED_KEY = "validation.station.address.required";
    public static final String STATION_ADDRESS_MAX_LENGTH_KEY = "validation.station.address.maxLength";
    public static final String STATION_PROVINCE_CODE_REQUIRED_KEY = "validation.station.provinceCode.required";
    public static final String STATION_PROVINCE_CODE_INVALID_KEY = "validation.station.provinceCode.invalid";
    public static final String STATION_WARD_CODE_REQUIRED_KEY = "validation.station.wardCode.required";
    public static final String STATION_WARD_CODE_INVALID_KEY = "validation.station.wardCode.invalid";
    public static final String STATION_LATITUDE_REQUIRED_KEY = "validation.station.latitude.required";
    public static final String STATION_LATITUDE_INVALID_KEY = "validation.station.latitude.invalid";
    public static final String STATION_LONGITUDE_REQUIRED_KEY = "validation.station.longitude.required";
    public static final String STATION_LONGITUDE_INVALID_KEY = "validation.station.longitude.invalid";
    public static final String STATION_CONTACT_PHONE_REQUIRED_KEY = "validation.station.contactPhone.required";
    public static final String STATION_CONTACT_PHONE_INVALID_KEY = "validation.station.contactPhone.invalid";
    public static final String STATION_CONTACT_PHONE_MAX_LENGTH_KEY = "validation.station.contactPhone.maxLength";
    public static final String STATION_PLANNED_CHARGE_POINT_COUNT_KEY =
            "validation.station.plannedChargePointCount.min";

    public static final ErrorMessage.Template STATION_NAME_REQUIRED =
            template(STATION_NAME_REQUIRED_KEY, "Station name is required");
    public static final ErrorMessage.Template STATION_NAME_MAX_LENGTH =
            template(STATION_NAME_MAX_LENGTH_KEY, "Station name cannot exceed 100 characters");
    public static final ErrorMessage.Template STATION_DESCRIPTION_MAX_LENGTH =
            template(STATION_DESCRIPTION_MAX_LENGTH_KEY, "Station description cannot exceed 500 characters");
    public static final ErrorMessage.Template STATION_ADDRESS_REQUIRED =
            template(STATION_ADDRESS_REQUIRED_KEY, "Station address is required");
    public static final ErrorMessage.Template STATION_ADDRESS_MAX_LENGTH =
            template(STATION_ADDRESS_MAX_LENGTH_KEY, "Station address cannot exceed 200 characters");
    public static final ErrorMessage.Template STATION_PROVINCE_CODE_REQUIRED =
            template(STATION_PROVINCE_CODE_REQUIRED_KEY, "Station province code is required");
    public static final ErrorMessage.Template STATION_PROVINCE_CODE_INVALID =
            template(STATION_PROVINCE_CODE_INVALID_KEY, "Station province code must contain digits only");
    public static final ErrorMessage.Template STATION_WARD_CODE_REQUIRED =
            template(STATION_WARD_CODE_REQUIRED_KEY, "Station ward code is required");
    public static final ErrorMessage.Template STATION_WARD_CODE_INVALID =
            template(STATION_WARD_CODE_INVALID_KEY, "Station ward code must contain digits only");
    public static final ErrorMessage.Template STATION_LATITUDE_REQUIRED =
            template(STATION_LATITUDE_REQUIRED_KEY, "Station latitude is required");
    public static final ErrorMessage.Template STATION_LATITUDE_INVALID =
            template(STATION_LATITUDE_INVALID_KEY, "Station latitude must be a valid number between -90 and 90");
    public static final ErrorMessage.Template STATION_LONGITUDE_REQUIRED =
            template(STATION_LONGITUDE_REQUIRED_KEY, "Station longitude is required");
    public static final ErrorMessage.Template STATION_LONGITUDE_INVALID =
            template(STATION_LONGITUDE_INVALID_KEY, "Station longitude must be a valid number between -180 and 180");
    public static final ErrorMessage.Template STATION_CONTACT_PHONE_REQUIRED =
            template(STATION_CONTACT_PHONE_REQUIRED_KEY, "Station contact phone is required");
    public static final ErrorMessage.Template STATION_CONTACT_PHONE_INVALID =
            template(STATION_CONTACT_PHONE_INVALID_KEY, "Station contact phone is invalid");
    public static final ErrorMessage.Template STATION_CONTACT_PHONE_MAX_LENGTH =
            template(STATION_CONTACT_PHONE_MAX_LENGTH_KEY, "Station contact phone cannot exceed 20 characters");
    public static final ErrorMessage.Template STATION_PLANNED_CHARGE_POINT_COUNT_MIN =
            template(STATION_PLANNED_CHARGE_POINT_COUNT_KEY,
                    "Planned charge point count must be greater than zero");

    public static final ErrorMessage.Template STATION_NOT_FOUND =
            template("error.station.notFound", "Station not found: {0}");
    public static final ErrorMessage.Template STATION_ACCESS_DENIED =
            template("error.station.accessDenied", "You do not have permission to manage this station");
    public static final ErrorMessage.Template INVALID_STATUS_TRANSITION =
            template("error.station.invalidStatusTransition", "Station status cannot change from {0} to {1}");
    public static final ErrorMessage.Template ACTIVE_LICENSE_ALREADY_EXISTS =
            template("error.station.activeLicenseAlreadyExists", "The station already has an active license");
    public static final ErrorMessage.Template LICENSE_NOT_FOUND =
            template("error.station.licenseNotFound", "License not found: {0}");
    public static final ErrorMessage.Template CHARGE_POINT_NOT_FOUND =
            template("error.station.chargePointNotFound", "Charge point not found: {0}");
    public static final ErrorMessage.Template CHARGE_POINT_CODE_ALREADY_EXISTS =
            template("error.station.chargePointCodeAlreadyExists", "Charge point code already exists: {0}");
    public static final ErrorMessage.Template CONNECTOR_NOT_FOUND =
            template("error.station.connectorNotFound", "Connector not found: {0}");
    public static final ErrorMessage.Template CONNECTOR_CODE_ALREADY_EXISTS =
            template("error.station.connectorCodeAlreadyExists", "Connector code already exists: {0}");
    public static final ErrorMessage.Template STATION_SUSPENSION_REASON_REQUIRED =
            template("error.station.suspensionReasonRequired", "A suspension reason is required");

    static List<ErrorMessage.Template> templates() {
        return List.of(
                STATION_NAME_REQUIRED,
                STATION_NAME_MAX_LENGTH,
                STATION_DESCRIPTION_MAX_LENGTH,
                STATION_ADDRESS_REQUIRED,
                STATION_ADDRESS_MAX_LENGTH,
                STATION_PROVINCE_CODE_REQUIRED,
                STATION_PROVINCE_CODE_INVALID,
                STATION_WARD_CODE_REQUIRED,
                STATION_WARD_CODE_INVALID,
                STATION_LATITUDE_REQUIRED,
                STATION_LATITUDE_INVALID,
                STATION_LONGITUDE_REQUIRED,
                STATION_LONGITUDE_INVALID,
                STATION_CONTACT_PHONE_REQUIRED,
                STATION_CONTACT_PHONE_INVALID,
                STATION_CONTACT_PHONE_MAX_LENGTH,
                STATION_PLANNED_CHARGE_POINT_COUNT_MIN,
                STATION_NOT_FOUND,
                STATION_ACCESS_DENIED,
                INVALID_STATUS_TRANSITION,
                ACTIVE_LICENSE_ALREADY_EXISTS,
                LICENSE_NOT_FOUND,
                CHARGE_POINT_NOT_FOUND,
                CHARGE_POINT_CODE_ALREADY_EXISTS,
                CONNECTOR_NOT_FOUND,
                CONNECTOR_CODE_ALREADY_EXISTS,
                STATION_SUSPENSION_REASON_REQUIRED
        );
    }
}
