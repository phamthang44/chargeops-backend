package com.thang.chargeops.notification.model;

import java.time.Instant;
import java.util.UUID;

/** All reads and mutations use the same server-resolved account and view. */
public record NotificationInboxFilter(UUID recipientId, NotificationAudience context, String category,
                                      UUID stationId, boolean unread, Instant now) {}
