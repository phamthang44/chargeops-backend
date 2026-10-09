package com.thang.chargeops.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * FAQ request: supports optional conversationId to continue existing threads.
 */
public record AssistantAskRequest(
        @NotBlank @Size(max = 2000) String question,
        @Pattern(regexp = "vi|en") String locale,
        @Size(max = 64) String conversationId
) {
    public AssistantAskRequest(String question, String locale) {
        this(question, locale, null);
    }
}
