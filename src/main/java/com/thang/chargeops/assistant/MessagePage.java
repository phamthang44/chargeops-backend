package com.thang.chargeops.assistant;

import java.util.List;

public record MessagePage(
        List<ChatTurn> items,
        boolean hasMore,
        String nextCursor
) { }
