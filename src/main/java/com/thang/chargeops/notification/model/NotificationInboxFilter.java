package com.thang.chargeops.notification.model;

import java.time.Instant;
import java.util.UUID;

/**
 * All reads and mutations use the same server-resolved account and view.
 * {@code recipientKeycloakId} is the wire identity of the same account, resolved once from the
 * authoritative profile load so a realtime hint never needs a second query (BKG-071).
 */
public record NotificationInboxFilter(UUID recipientId, String recipientKeycloakId,
                                      NotificationAudience context, String category,
                                      UUID stationId, boolean unread, Instant now) {}
