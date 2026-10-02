package com.thang.chargeops.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record AppNotificationResponse(UUID id, String category, String title, String body,
                                      String actionUrl, Instant createdAt, boolean read, Instant readAt) {}
