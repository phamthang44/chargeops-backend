package com.thang.chargeops.support.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TicketEventResponse(UUID id, UUID ticketId, UUID actorId, String actorKind, String eventType,
                                  String fromStatus, String toStatus, UUID fromHandlerId,
                                  UUID toHandlerId, int resolutionCycle, String reason, Instant createdAt,
                                  String actorName, String fromHandlerName, String toHandlerName) {}
