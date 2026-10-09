package com.thang.chargeops.assistant;

import java.time.Instant;

public record ConversationSummary(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt
) { }
