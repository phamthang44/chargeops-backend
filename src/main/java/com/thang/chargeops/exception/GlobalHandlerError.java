package com.thang.chargeops.exception;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.*;


/**
 * Central exception handler. Translates exceptions into the standard
 * {@link ApiResult} error envelope: a stable {@code code}, a trace id, and a
 * fixed English default message (for logs). Clients localize by {@code code} —
 * the backend does not translate.
 *
 * <p>Domain-specific handlers (e.g. booking-overlap from the {@code EXCLUDE USING gist}
 * constraint) can be added as the corresponding domain error codes are introduced.
 */
@RestControllerAdvice
@Slf4j
public class GlobalHandlerError {

    // --- Business logic errors ---
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResult<?>> handleAppException(AppException e) {
        String traceId = newTraceId();
        log.warn("Business error [{}]: {} - code={}", traceId, e.getMessage(), e.getErrorCodeStr());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResult.error(e.getErrorCodeStr(), e.getMessage(), traceId));
    }

    // --- Validation errors (400) ---
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResult<?>> handleValidationException(Exception e) {
        String traceId = newTraceId();
        log.warn("Validation error [{}]: {}", traceId, e.getMessage());

        Object details = null;
        String message = "Validation failed";

        if (e instanceof MethodArgumentNotValidException ex) {
            BindingResult result = ex.getBindingResult();
            Map<String, String> errors = new HashMap<>();
            String first = null;
            for (FieldError fe : result.getFieldErrors()) {
                String msg = fe.getDefaultMessage();
                errors.put(fe.getField(), msg);
                if (first == null) {
                    first = msg;
                }
            }
            details = errors;
            message = (first != null) ? first : "Invalid input data";
        } else if (e instanceof ConstraintViolationException ex) {
            details = ex.getConstraintViolations().stream()
                    .collect(Collectors.toMap(
                            v -> v.getPropertyPath().toString(),
                            v -> v.getMessage(),
                            (a, b) -> a));
            message = "Invalid parameters";
        } else if (e instanceof MissingServletRequestParameterException ex) {
            message = "Missing required parameter: " + ex.getParameterName();
        } else if (e instanceof IllegalArgumentException ex && ex.getMessage() != null) {
            message = ex.getMessage();
        }

        return ResponseEntity.status(BAD_REQUEST)
                .body(ApiResult.error(CommonErrorCode.INVALID_REQUEST.getCode(), message, traceId, details));
    }

    // --- Access denied (403) ---
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<?>> handleAccessDenied(AccessDeniedException e) {
        String traceId = newTraceId();
        log.warn("Access denied [{}]: {}", traceId, e.getMessage());
        return ResponseEntity.status(FORBIDDEN)
                .body(ApiResult.error(AuthErrorCode.ACCESS_DENIED.getCode(),
                        AuthErrorCode.ACCESS_DENIED.getMessage(), traceId));
    }

    // --- Not found (404) ---
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResult<?>> handleNotFound(HttpServletRequest request, Exception e) {
        String traceId = newTraceId();
        return ResponseEntity.status(NOT_FOUND)
                .body(ApiResult.error(CommonErrorCode.RESOURCE_NOT_FOUND.getCode(),
                        CommonErrorCode.RESOURCE_NOT_FOUND.format(request.getRequestURI()), traceId));
    }

    // --- Method not allowed (405) ---
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        String traceId = newTraceId();
        return ResponseEntity.status(METHOD_NOT_ALLOWED)
                .body(ApiResult.error(CommonErrorCode.METHOD_NOT_ALLOWED.getCode(), e.getMessage(), traceId));
    }

    // --- Malformed / unreadable request body (400) ---
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<?>> handleNotReadable(HttpMessageNotReadableException e) {
        String traceId = newTraceId();
        Throwable root = e.getMostSpecificCause();
        String message = "invalid.request.format";

        if (root instanceof InvalidFormatException ife) {
            String field = ife.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .collect(Collectors.joining("."));
            boolean numeric = Number.class.isAssignableFrom(ife.getTargetType()) || ife.getTargetType().isPrimitive();
            message = numeric
                    ? "Value out of range for field: " + field
                    : "Invalid format for field: " + field;
        } else if (root instanceof StreamReadException) {
            message = "Malformed JSON request";
        }

        log.warn("Unreadable request [{}]: {}", traceId, root != null ? root.getMessage() : e.getMessage());
        return ResponseEntity.status(BAD_REQUEST)
                .body(ApiResult.error(CommonErrorCode.INVALID_REQUEST.getCode(), message, traceId));
    }

    // --- Optimistic locking conflict (409) ---
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<?>> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
        String traceId = newTraceId();
        log.warn("Optimistic locking conflict [{}]: {}", traceId, ex.getMessage());
        return ResponseEntity.status(CommonErrorCode.RESOURCE_CONFLICT.getHttpStatus())
                .body(ApiResult.error(CommonErrorCode.RESOURCE_CONFLICT.getCode(),
                        CommonErrorCode.RESOURCE_CONFLICT.getMessage(), traceId));
    }

    // --- Data integrity violation (409) ---
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResult<?>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String traceId = newTraceId();
        String rootMsg = ex.getRootCause() != null ? ex.getRootCause().getMessage() : "";
        log.error("{} | Data integrity error [{}]: {}", LogConstant.SYS_ERROR, traceId, rootMsg);
        return ResponseEntity.status(CommonErrorCode.DATA_INTEGRITY_ERROR.getHttpStatus())
                .body(ApiResult.error(CommonErrorCode.DATA_INTEGRITY_ERROR.getCode(),
                        CommonErrorCode.DATA_INTEGRITY_ERROR.getMessage(), traceId));
    }

    // --- Catch-all (500) ---
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<?>> handleGlobal(Exception e) {
        String traceId = newTraceId();
        log.error("Internal server error [{}]", traceId, e);
        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(CommonErrorCode.INTERNAL_ERROR.getCode(),
                        CommonErrorCode.INTERNAL_ERROR.getMessage(), traceId));
    }

    private String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
