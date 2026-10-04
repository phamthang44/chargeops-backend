package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.booking.decision.*;
import java.time.Instant;
import java.util.UUID;

/** Public reason and authority only: no command, actor profile IDs or evidence dossier. */
public record ServiceFailureDecisionResponse(
        UUID decisionId,
        long sequenceNo,
        ServiceFailureDecisionKind kind,
        ServiceFailureDecisionResult result,
        String reason,
        Instant affectedAt,
        Instant decidedAt,
        String actorKind,
        String actorDisplayName,
        UUID refundId) {
    public static ServiceFailureDecisionResponse from(BookingServiceFailureDecision decision) {
        return new ServiceFailureDecisionResponse(
                decision.getId(),
                decision.getSequenceNo(),
                decision.getKind(),
                decision.getResult(),
                decision.getReason(),
                decision.getAffectedAt(),
                decision.getDecidedAt(),
                decision.getActorKind(),
                decision.getActorDisplayNameSnapshot(),
                decision.getRefund() == null ? null : decision.getRefund().getId());
    }
}
