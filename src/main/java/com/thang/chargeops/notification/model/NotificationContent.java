package com.thang.chargeops.notification.model;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.NotificationErrorCode;
import java.time.Instant;
import java.util.Objects;

import static com.thang.chargeops.notification.model.NotificationConstants.*;

public record NotificationContent(String title, String body, Instant createdAt) {
    public NotificationContent {
        validateText(title, TITLE_MAX_LENGTH);
        validateText(body, BODY_MAX_LENGTH);
        Objects.requireNonNull(createdAt, "Notification creation time is required");
    }

    private static void validateText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new AppException(NotificationErrorCode.INVALID_CONTENT);
        }
    }
}
