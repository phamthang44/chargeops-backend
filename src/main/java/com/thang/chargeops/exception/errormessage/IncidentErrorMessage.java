package com.thang.chargeops.exception.errormessage;

import java.util.List;
import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class IncidentErrorMessage {
    private IncidentErrorMessage() { }
    public static final ErrorMessage.Template VERSION_CONFLICT = template(
            "error.incident.versionConflict", "Incident or connector changed; refresh before confirming again");
    public static final ErrorMessage.Template STATE_CONFLICT = template(
            "error.incident.stateConflict", "The incident command is not allowed in the current state");
    public static final ErrorMessage.Template OPEN_INCIDENT = template(
            "error.incident.openIncident", "Recover all open incidents before making this connector available");
    public static final ErrorMessage.Template ALREADY_OPEN = template(
            "error.incident.alreadyOpen", "Connector already has an active incident; resolve it before reporting a new one");
    static List<ErrorMessage.Template> templates() {
        return List.of(VERSION_CONFLICT, STATE_CONFLICT, OPEN_INCIDENT, ALREADY_OPEN);
    }
}
