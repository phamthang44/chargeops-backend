package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.booking.dto.response.ServiceFailureContextResponse;
import java.util.UUID;

/** Exact case scope; no Owner ledger or private evidence dossier. */
public record RefundPolicyContextResponse(
        UUID ticketId, UUID escalationId, long ticketVersion, boolean active,
        ServiceFailureContextResponse booking,
        ServiceFailureContextResponse.Availability grant,
        ServiceFailureContextResponse.Availability insufficient) {}
