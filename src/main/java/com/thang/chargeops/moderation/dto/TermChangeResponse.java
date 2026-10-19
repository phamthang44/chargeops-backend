package com.thang.chargeops.moderation.dto;

import java.time.Instant;
import java.util.UUID;

/** Snapshots use the same JSON representation as ProhibitedTermResponse. */
public record TermChangeResponse(UUID id, UUID termId, String action, UUID actorId, Instant changedAt,
                                String beforeSnapshot, String afterSnapshot) { }
