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
    public static final String STATUS_CHANGE_REASON_REQUIRED_KEY = "validation.reason.required";
    public static final String STATUS_CHANGE_REASON_SIZE_KEY = "validation.station.reason.size";
    public static final String CHARGE_POINT_CODE_MAX_LENGTH_KEY = "validation.chargePoint.code.maxLength";
    public static final String CHARGE_POINT_CODE_FORMAT_KEY = "validation.chargePoint.code.format";
    public static final String CHARGE_POINT_NAME_REQUIRED_KEY = "validation.chargePoint.name.required";
    public static final String CHARGE_POINT_NAME_MAX_LENGTH_KEY = "validation.chargePoint.name.maxLength";
    public static final String CHARGE_POINT_ZONE_MAX_LENGTH_KEY = "validation.chargePoint.zone.maxLength";
    public static final String CHARGE_POINT_MAX_POWER_MIN_KEY = "validation.chargePoint.maxPower.min";
    public static final String CHARGE_POINT_MAX_POWER_MAX_KEY = "validation.chargePoint.maxPower.max";
    public static final String CHARGE_POINT_UPDATE_REQUIRED_KEY = "validation.chargePoint.update.required";
    public static final String CONNECTOR_UPDATE_REQUIRED_KEY = "validation.connector.update.required";
    public static final String CONNECTOR_CODE_MAX_LENGTH_KEY = "validation.connector.code.maxLength";
    public static final String CHARGE_POINT_CONNECTOR_GROUPS_REQUIRED_KEY =
            "validation.chargePoint.connectorGroups.required";
    public static final String CHARGE_POINT_CONNECTOR_COUNT_MAX_KEY =
            "validation.chargePoint.connectorCount.max";
    public static final String CONNECTOR_CODE_FORMAT_KEY = "validation.connector.code.format";
    public static final String CONNECTOR_TYPE_REQUIRED_KEY = "validation.connector.type.required";
    public static final String CONNECTOR_POWER_REQUIRED_KEY = "validation.connector.power.required";
    public static final String CONNECTOR_POWER_MIN_KEY = "validation.connector.power.min";
    public static final String CONNECTOR_POWER_MAX_KEY = "validation.connector.power.max";
    public static final String CONNECTOR_QUANTITY_REQUIRED_KEY = "validation.connector.quantity.required";
    public static final String CONNECTOR_QUANTITY_MIN_KEY = "validation.connector.quantity.min";
    public static final String CONNECTOR_RUNTIME_STATUS_REQUIRED_KEY =
            "validation.connector.runtimeStatus.required";
    public static final String CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED_KEY =
            "validation.chargePoint.operationalStatus.required";
    public static final String CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_REQUIRED_KEY =
            "validation.chargePoint.expectedConnectorCount.required";
    public static final String CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_MIN_KEY =
            "validation.chargePoint.expectedConnectorCount.min";
    public static final String PAGE_NUMBER_MIN_KEY = "validation.pagination.page.min";
    public static final String PAGE_SIZE_MIN_KEY = "validation.pagination.size.min";
    public static final String PAGE_SIZE_MAX_KEY = "validation.pagination.size.max";

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
            template(STATION_PLANNED_CHARGE_POINT_COUNT_KEY, "Planned charge point count must be greater than zero");
    public static final ErrorMessage.Template STATUS_CHANGE_REASON_REQUIRED =
            template(STATUS_CHANGE_REASON_REQUIRED_KEY, "Status change reason is required");
    public static final ErrorMessage.Template STATUS_CHANGE_REASON_SIZE =
            template(STATUS_CHANGE_REASON_SIZE_KEY, "Status change reason size is max 500 characters");
    public static final ErrorMessage.Template CHARGE_POINT_CODE_MAX_LENGTH =
            template(CHARGE_POINT_CODE_MAX_LENGTH_KEY, "Charge point code cannot exceed 80 characters");
    public static final ErrorMessage.Template CHARGE_POINT_CODE_FORMAT =
            template(CHARGE_POINT_CODE_FORMAT_KEY, "Charge point code may contain letters, digits, hyphens and underscores only");
    public static final ErrorMessage.Template CHARGE_POINT_NAME_REQUIRED =
            template(CHARGE_POINT_NAME_REQUIRED_KEY, "Charge point name is required");
    public static final ErrorMessage.Template CHARGE_POINT_NAME_MAX_LENGTH =
            template(CHARGE_POINT_NAME_MAX_LENGTH_KEY, "Charge point name cannot exceed 100 characters");
    public static final ErrorMessage.Template CHARGE_POINT_ZONE_MAX_LENGTH =
            template(CHARGE_POINT_ZONE_MAX_LENGTH_KEY, "Charge point zone cannot exceed 100 characters");
    public static final ErrorMessage.Template CHARGE_POINT_MAX_POWER_MIN =
            template(CHARGE_POINT_MAX_POWER_MIN_KEY, "Charge point maximum power must be at least 3.0 kW");
    public static final ErrorMessage.Template CHARGE_POINT_MAX_POWER_MAX =
            template(CHARGE_POINT_MAX_POWER_MAX_KEY, "Charge point maximum power cannot exceed 720.0 kW");
    public static final ErrorMessage.Template CHARGE_POINT_UPDATE_REQUIRED =
            template(CHARGE_POINT_UPDATE_REQUIRED_KEY, "At least one charge point field must be supplied");
    public static final ErrorMessage.Template CHARGE_POINT_CONNECTOR_GROUPS_REQUIRED =
            template(CHARGE_POINT_CONNECTOR_GROUPS_REQUIRED_KEY, "At least one connector group is required");
    public static final ErrorMessage.Template CHARGE_POINT_CONNECTOR_COUNT_MAX =
            template(CHARGE_POINT_CONNECTOR_COUNT_MAX_KEY, "A charge point can contain at most 8 connectors");
    public static final ErrorMessage.Template CONNECTOR_CODE_MAX_LENGTH =
            template(CONNECTOR_CODE_MAX_LENGTH_KEY, "Connector code cannot exceed 50 characters");
    public static final ErrorMessage.Template CONNECTOR_CODE_FORMAT =
            template(CONNECTOR_CODE_FORMAT_KEY, "Connector code may contain letters, digits, hyphens and underscores only");
    public static final ErrorMessage.Template CONNECTOR_TYPE_REQUIRED_VALIDATION =
            template(CONNECTOR_TYPE_REQUIRED_KEY, "Connector type is required");
    public static final ErrorMessage.Template CONNECTOR_POWER_REQUIRED =
            template(CONNECTOR_POWER_REQUIRED_KEY, "Connector power is required");
    public static final ErrorMessage.Template CONNECTOR_POWER_MIN =
            template(CONNECTOR_POWER_MIN_KEY, "Connector power must be at least 3.0 kW");
    public static final ErrorMessage.Template CONNECTOR_POWER_MAX =
            template(CONNECTOR_POWER_MAX_KEY, "Connector power cannot exceed 360.0 kW");
    public static final ErrorMessage.Template CONNECTOR_QUANTITY_REQUIRED =
            template(CONNECTOR_QUANTITY_REQUIRED_KEY, "Connector quantity is required");
    public static final ErrorMessage.Template CONNECTOR_QUANTITY_MIN =
            template(CONNECTOR_QUANTITY_MIN_KEY, "Connector quantity must be at least 1");
    public static final ErrorMessage.Template CONNECTOR_RUNTIME_STATUS_REQUIRED_VALIDATION =
            template(CONNECTOR_RUNTIME_STATUS_REQUIRED_KEY, "Connector runtime status is required");
    public static final ErrorMessage.Template CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED_VALIDATION =
            template(CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED_KEY, "Charge point operational status is required");
    public static final ErrorMessage.Template CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_REQUIRED =
            template(CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_REQUIRED_KEY, "Expected connector count is required for activation");
    public static final ErrorMessage.Template CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_MIN =
            template(CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_MIN_KEY, "Expected connector count must be at least 1");
    public static final ErrorMessage.Template PAGE_NUMBER_MIN =
            template(PAGE_NUMBER_MIN_KEY, "Page number must be at least 1");
    public static final ErrorMessage.Template PAGE_SIZE_MIN =
            template(PAGE_SIZE_MIN_KEY, "Page size must be at least 1");
    public static final ErrorMessage.Template PAGE_SIZE_MAX =
            template(PAGE_SIZE_MAX_KEY, "Page size cannot exceed 100");

    public static final ErrorMessage.Template STATION_NOT_FOUND =
            template("error.station.notFound", "Station not found: {0}");
    public static final ErrorMessage.Template STATION_ACCESS_DENIED =
            template("error.station.accessDenied", "You do not have permission to manage this station");
    public static final ErrorMessage.Template INVALID_STATUS_TRANSITION =
            template("error.station.invalidStatusTransition", "Station status cannot change from {0} to {1}");
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
    public static final ErrorMessage.Template STATION_REACTIVATION_REASON_REQUIRED =
            template("error.station.reactivationReasonRequired", "A reactivation reason is required");
    public static final ErrorMessage.Template INVALID_CHECK_IN_CHALLENGE =
            template("error.station.invalidCheckInChallenge", "Invalid or expired check-in challenge token");
    public static final ErrorMessage.Template CONNECTOR_POWER_EXCEEDS_MAX_POWER =
            template("error.station.connectorPowerExceedsMaxPower", "Connector power ({0} kW) cannot exceed charge point maximum power ({1} kW)");
    public static final ErrorMessage.Template CONNECTOR_POWER_RANGE_INVALID =
            template("error.station.connectorPowerRangeInvalid", "Connector power must be between 3.0 kW and 360.0 kW");
    public static final ErrorMessage.Template CHARGE_POINT_MAX_POWER_RANGE_INVALID =
            template("error.station.chargePointMaxPowerRangeInvalid", "Charge point maximum power must be between 3.0 kW and 720.0 kW");
    public static final ErrorMessage.Template CONNECTOR_TYPE_MISMATCH =
            template("error.station.connectorTypeMismatch", "Connector type {0} is incompatible with charger type {1}");
    public static final ErrorMessage.Template CHARGE_POINT_CODE_REQUIRED =
            template("error.station.chargePointCodeRequired", "Charge point code is required");
    public static final ErrorMessage.Template CONNECTOR_CODE_REQUIRED =
            template("error.station.connectorCodeRequired", "Connector code is required");
    public static final ErrorMessage.Template CONNECTOR_HAS_ACTIVE_BOOKINGS =
            template("error.station.connectorHasActiveBookings", "Connector has a confirmed or checked-in booking");
    public static final ErrorMessage.Template CHARGE_POINT_HAS_ACTIVE_BOOKINGS =
            template("error.station.chargePointHasActiveBookings", "Charge point has a confirmed or checked-in booking");
    public static final ErrorMessage.Template CHARGE_POINT_REQUIRES_CONNECTOR =
            template("error.station.chargePointRequiresConnector", "Charge point requires at least one connector before activation");
    public static final ErrorMessage.Template INVALID_CHARGE_POINT_PROVISIONING_TRANSITION =
            template("error.station.invalidChargePointProvisioningTransition", "Charge point provisioning status cannot change from {0} to {1}");
    public static final ErrorMessage.Template CHARGE_POINT_SUSPENDED =
            template("error.station.chargePointSuspended", "An admin-suspended charge point cannot be changed by owner operations");
    public static final ErrorMessage.Template CONNECTOR_RUNTIME_STATUS_SYSTEM_MANAGED =
            template("error.station.connectorRuntimeStatusSystemManaged", "IN_USE runtime status is managed by the booking/session flow");
    public static final ErrorMessage.Template STATION_NOT_ELIGIBLE_FOR_PROVISIONING =
            template("error.station.notEligibleForProvisioning", "Station status {0} is not eligible for charge-point provisioning");
    public static final ErrorMessage.Template CHARGE_POINT_STATUS_REASON_REQUIRED =
            template("error.station.chargePointStatusReasonRequired", "A reason is required when disabling or suspending a charge point");
    public static final ErrorMessage.Template CONNECTOR_STATUS_REASON_REQUIRED =
            template("error.station.connectorStatusReasonRequired", "A reason is required when taking a connector offline");
    public static final ErrorMessage.Template CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED =
            template("error.station.chargePointOperationalStatusRequired", "Charge point operational status is required");
    public static final ErrorMessage.Template CONNECTOR_RUNTIME_STATUS_REQUIRED =
            template("error.station.connectorRuntimeStatusRequired", "Connector runtime status is required");
    public static final ErrorMessage.Template CONNECTOR_HARDWARE_LOCKED =
            template("error.station.connectorHardwareLocked", "Connector hardware cannot be changed after charge-point activation");
    public static final ErrorMessage.Template CONNECTOR_NOT_AVAILABLE_FOR_CHECK_IN =
            template("error.station.connectorNotAvailableForCheckIn", "Connector is not available for check-in: {0}");
    public static final ErrorMessage.Template CHARGE_POINT_CONNECTOR_COUNT_MISMATCH =
            template("error.station.chargePointConnectorCountMismatch", "Expected {0} connectors but found {1}; review inventory before activation");

    public static final ErrorMessage.Template CHARGE_POINT_DRAFT_DELETE_ONLY =
            template("error.station.chargePointDraftDeleteOnly", "Only a pending-activation charge point can be deleted");
    public static final ErrorMessage.Template CONNECTOR_DRAFT_DELETE_ONLY =
            template("error.station.connectorDraftDeleteOnly", "Only connectors of a pending-activation charge point can be deleted");
    public static final ErrorMessage.Template STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS =
            template("error.station.notEligibleForNewBusiness", "Station is not eligible for new bookings at this time");
    public static final ErrorMessage.Template CONNECTOR_NOT_BOOKABLE =
            template("error.station.connectorNotBookable", "Connector is not bookable at this time: {0}");
    public static final ErrorMessage.Template PRICING_MIN_BOOKING_DURATION_INVALID =
            template(
                    "error.station.pricing.minBookingDurationInvalid",
                    "Minimum booking duration must be exactly 30, 60, or 90 minutes"
            );
    public static final ErrorMessage.Template PRICING_OPERATING_WEEK_INVALID =
            template(
                    "error.station.pricing.operatingWeekInvalid",
                    "Operating hours must contain every day exactly once"
            );
    public static final ErrorMessage.Template PRICING_CLOSED_DAY_TIME_PRESENT =
            template(
                    "error.station.pricing.closedDayTimePresent",
                    "A closed day must not contain open or close time"
            );
    public static final ErrorMessage.Template PRICING_OPEN_DAY_TIME_REQUIRED =
            template(
                    "error.station.pricing.openDayTimeRequired",
                    "An enabled day requires both open and close time"
            );
    public static final ErrorMessage.Template PRICING_OPERATING_WINDOW_AMBIGUOUS =
            template(
                    "error.station.pricing.operatingWindowAmbiguous",
                    "Equal open and close time is ambiguous; use the 24/7 option instead"
            );
    public static final ErrorMessage.Template PRICING_TOU_RULES_REQUIRED =
            template(
                    "error.station.pricing.touRulesRequired",
                    "TOU rules are required; use an empty list when only base price applies"
            );
    public static final ErrorMessage.Template PRICING_TOU_NAME_DUPLICATED =
            template(
                    "error.station.pricing.touNameDuplicated",
                    "TOU rule names must be unique"
            );
    public static final ErrorMessage.Template PRICING_TOU_WINDOW_INVALID =
            template(
                    "error.station.pricing.touWindowInvalid",
                    "A TOU rule must have a non-zero time window"
            );
    public static final ErrorMessage.Template PRICING_TOU_RATE_INVALID =
            template(
                    "error.station.pricing.touRateInvalid",
                    "TOU rate must be greater than zero"
            );
    public static final ErrorMessage.Template PRICING_TOU_RULES_OVERLAP =
            template(
                    "error.station.pricing.touRulesOverlap",
                    "TOU rules overlap after applying DAILY/WEEKDAY/WEEKEND day groups"
            );
    public static final ErrorMessage.Template PRICING_BASE_PRICE_INVALID =
            template(
                    "error.station.pricing.basePriceInvalid",
                    "Base price must be greater than zero"
            );
    public static final ErrorMessage.Template PRICING_TOU_NAME_REQUIRED =
            template(
                    "error.station.pricing.touNameRequired",
                    "TOU rule name is required"
            );
    public static final ErrorMessage.Template PRICING_OPEN_24_HOURS_PERIOD_NOT_ALLOWED =
            template(
                    "error.station.pricing.open24HoursPeriodNotAllowed",
                    "A 24/7 operating schedule cannot contain daily periods"
            );
    public static final ErrorMessage.Template PRICING_CONFIGURATION_CONFLICT =
            template(
                    "error.station.pricing.configurationConflict",
                    "Pricing configuration changed concurrently or is no longer active"
            );

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
                STATUS_CHANGE_REASON_REQUIRED,
                STATUS_CHANGE_REASON_SIZE,
                CHARGE_POINT_CODE_MAX_LENGTH,
                CHARGE_POINT_CODE_FORMAT,
                CHARGE_POINT_NAME_REQUIRED,
                CHARGE_POINT_NAME_MAX_LENGTH,
                CHARGE_POINT_ZONE_MAX_LENGTH,
                CHARGE_POINT_MAX_POWER_MIN,
                CHARGE_POINT_MAX_POWER_MAX,
                CHARGE_POINT_UPDATE_REQUIRED,
                CONNECTOR_CODE_MAX_LENGTH,
                CONNECTOR_CODE_FORMAT,
                CONNECTOR_TYPE_REQUIRED_VALIDATION,
                CONNECTOR_POWER_REQUIRED,
                CONNECTOR_POWER_MIN,
                CONNECTOR_POWER_MAX,
                CONNECTOR_QUANTITY_REQUIRED,
                CONNECTOR_QUANTITY_MIN,
                CONNECTOR_RUNTIME_STATUS_REQUIRED_VALIDATION,
                CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED_VALIDATION,
                CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_REQUIRED,
                CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_MIN,
                PAGE_NUMBER_MIN,
                PAGE_SIZE_MIN,
                PAGE_SIZE_MAX,
                STATION_NOT_FOUND,
                STATION_ACCESS_DENIED,
                INVALID_STATUS_TRANSITION,
                CHARGE_POINT_NOT_FOUND,
                CHARGE_POINT_CONNECTOR_GROUPS_REQUIRED,
                CHARGE_POINT_CONNECTOR_COUNT_MAX,
                CHARGE_POINT_CODE_ALREADY_EXISTS,
                CONNECTOR_NOT_FOUND,
                CONNECTOR_CODE_ALREADY_EXISTS,
                STATION_SUSPENSION_REASON_REQUIRED,
                STATION_REACTIVATION_REASON_REQUIRED,
                INVALID_CHECK_IN_CHALLENGE,
                CONNECTOR_POWER_EXCEEDS_MAX_POWER,
                CONNECTOR_POWER_RANGE_INVALID,
                CHARGE_POINT_MAX_POWER_RANGE_INVALID,
                CONNECTOR_TYPE_MISMATCH,
                CHARGE_POINT_CODE_REQUIRED,
                CONNECTOR_CODE_REQUIRED,
                CONNECTOR_HAS_ACTIVE_BOOKINGS,
                CHARGE_POINT_HAS_ACTIVE_BOOKINGS,
                CHARGE_POINT_REQUIRES_CONNECTOR,
                INVALID_CHARGE_POINT_PROVISIONING_TRANSITION,
                CHARGE_POINT_SUSPENDED,
                CONNECTOR_RUNTIME_STATUS_SYSTEM_MANAGED,
                STATION_NOT_ELIGIBLE_FOR_PROVISIONING,
                CHARGE_POINT_STATUS_REASON_REQUIRED,
                CONNECTOR_STATUS_REASON_REQUIRED,
                CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED,
                CONNECTOR_RUNTIME_STATUS_REQUIRED,
                CONNECTOR_HARDWARE_LOCKED,
                CONNECTOR_NOT_AVAILABLE_FOR_CHECK_IN,
                CHARGE_POINT_CONNECTOR_COUNT_MISMATCH,
                CHARGE_POINT_DRAFT_DELETE_ONLY,
                CONNECTOR_DRAFT_DELETE_ONLY,
                STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS,
                CONNECTOR_NOT_BOOKABLE,
                PRICING_MIN_BOOKING_DURATION_INVALID,
                PRICING_OPERATING_WEEK_INVALID,
                PRICING_CLOSED_DAY_TIME_PRESENT,
                PRICING_OPEN_DAY_TIME_REQUIRED,
                PRICING_OPERATING_WINDOW_AMBIGUOUS,
                PRICING_TOU_RULES_REQUIRED,
                PRICING_TOU_NAME_DUPLICATED,
                PRICING_TOU_WINDOW_INVALID,
                PRICING_TOU_RATE_INVALID,
                PRICING_TOU_RULES_OVERLAP,
                PRICING_BASE_PRICE_INVALID,
                PRICING_TOU_NAME_REQUIRED,
                PRICING_OPEN_24_HOURS_PERIOD_NOT_ALLOWED,
                PRICING_CONFIGURATION_CONFLICT
        );
    }
}
