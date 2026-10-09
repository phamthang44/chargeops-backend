package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AssistantErrorCode implements BaseErrorCode {
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE", "error.assistant.unavailable",
            "The assistant is temporarily unavailable. Please try again later."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_RATE_LIMITED", "error.assistant.rateLimited",
            "Too many assistant requests. Please wait before trying again."),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_QUOTA_EXCEEDED", "error.assistant.quotaExceeded",
            "The daily assistant request limit has been reached. Please wait until it resets."),
    UPSTREAM_QUOTA_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UPSTREAM_QUOTA_EXCEEDED", "error.assistant.upstreamQuotaExceeded",
            "The assistant provider quota has been exhausted. Please contact the operator."),
    PROVIDER_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_PROVIDER_RATE_LIMITED", "error.assistant.providerRateLimited",
            "The assistant provider is busy. Please wait before trying again."),
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "ASSISTANT_TIMEOUT", "error.assistant.timeout",
            "The assistant did not respond in time. Please try again later."),
    INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "ASSISTANT_INVALID_RESPONSE", "error.assistant.invalidResponse",
            "The assistant could not return a valid answer. Please try again later."),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ASSISTANT_CONVERSATION_NOT_FOUND", "error.assistant.conversationNotFound",
            "The requested conversation was not found or does not belong to you.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String messageKey;
    private final String message;
}
