package com.thang.chargeops.exception.errorcode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Authentication/authorization errors produced by the backend as an OAuth2
 * resource server.
 *
 * <p>The backend only verifies Keycloak-issued JWTs and checks authorities — it
 * does not perform login, issue/refresh tokens, or manage passwords/MFA. Those
 * are owned by Keycloak, which returns its own errors at its endpoints. Hence
 * the small surface here: 401 (token problems) and 403 (insufficient role).
 *
 * <p>Messages are fixed English defaults (for logs); clients localize by
 * {@link #getCode()}. User-management codes (registration, ban/suspend, role
 * assignment) belong in a future {@code UserErrorCode}.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {
    // 401 — token missing, malformed, expired, or otherwise invalid
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "AUTH_001", "Authentication is required"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "Token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "Invalid token"),

    // 403 — authenticated but lacking the required role/authority
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_004", "You do not have permission to access this resource");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
