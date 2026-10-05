package com.thang.chargeops.support.dto.response;

import java.util.UUID;

public record TicketHandlerCandidateResponse(UUID userId, String displayName, String role) {
}
