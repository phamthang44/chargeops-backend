package com.thang.chargeops.assistant;

import java.util.List;

public record ConversationPage(
        List<ConversationSummary> items,
        boolean hasMore,
        String nextCursor
) { }
