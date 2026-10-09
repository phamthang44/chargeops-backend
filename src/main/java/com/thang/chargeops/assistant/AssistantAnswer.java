package com.thang.chargeops.assistant;

import java.util.List;

public record AssistantAnswer(
        String answer,
        String locale,
        String messageId,
        String conversationId,
        List<Citation> citations
) {
    public AssistantAnswer(String answer, String locale, String messageId, List<Citation> citations) {
        this(answer, locale, messageId, null, citations);
    }

    public record Citation(String documentId, String documentName, String segmentId, String content, Double score) { }
}
