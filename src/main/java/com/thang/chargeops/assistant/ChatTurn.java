package com.thang.chargeops.assistant;

import java.time.Instant;
import java.util.List;

public record ChatTurn(
        String id,
        String conversationId,
        String query,
        String answer,
        String status,
        Instant createdAt,
        List<AssistantAnswer.Citation> citations
) { }
