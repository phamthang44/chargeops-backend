package com.thang.chargeops.exception;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
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

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestControllerAdvice
@Slf4j
public class GlobalHandlerError {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResult<?>> handleAppException(AppException e) {
        String traceId = newTraceId();
        BaseErrorCode errorCode = e.getErrorCode();
        log.warn("Business error [{}]: {} - code={}", traceId, e.getMessage(), e.getErrorCodeStr());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResult.error(errorCode.getCode(), errorCode.getMessageKey(), e.getMessage(), traceId));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResult<?>> handleValidationException(Exception e) {
        String traceId = newTraceId();
        String messageKey = ErrorMessage.Validation.FAILED_KEY;
        String message = ErrorMessage.Validation.FAILED.defaultMessage();
        Object details = null;

        if (e instanceof MethodArgumentNotValidException ex) {
            BindingResult result = ex.getBindingResult();
            Map<String, ValidationFailure> errors = new HashMap<>();
            for (FieldError fieldError : result.getFieldErrors()) {
                errors.put(fieldError.getField(), toValidationFailure(fieldError.getDefaultMessage()));
            }
            details = errors;
        } else if (e instanceof ConstraintViolationException ex) {
            details = ex.getConstraintViolations().stream()
                    .collect(Collectors.toMap(
                            violation -> violation.getPropertyPath().toString(),
                            violation -> toValidationFailure(violation.getMessage()),
                            (left, right) -> left));
        } else if (e instanceof MissingServletRequestParameterException ex) {
            messageKey = ErrorMessage.Validation.REQUIRED_PARAMETER_KEY;
            message = ErrorMessage.Validation.REQUIRED_PARAMETER.format(ex.getParameterName());
            details = Map.of(ex.getParameterName(), new ValidationFailure(messageKey, message));
        } else if (e instanceof IllegalArgumentException ex) {
            messageKey = ErrorMessage.Validation.INVALID_INPUT_KEY;
            message = hasText(ex.getMessage()) ? ex.getMessage() : ErrorMessage.Validation.INVALID_INPUT.defaultMessage();
        }

        log.warn("Validation error [{}]: {}", traceId, e.getMessage());
        return ResponseEntity.status(BAD_REQUEST)
                .body(ApiResult.error(
                        CommonErrorCode.INVALID_REQUEST.getCode(),
                        messageKey,
                        message,
                        traceId,
                        details
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<?>> handleAccessDenied(AccessDeniedException e) {
        String traceId = newTraceId();
        log.warn("Access denied [{}]: {}", traceId, e.getMessage());
        return ResponseEntity.status(FORBIDDEN)
                .body(ApiResult.error(
                        AuthErrorCode.ACCESS_DENIED.getCode(),
                        AuthErrorCode.ACCESS_DENIED.getMessageKey(),
                        AuthErrorCode.ACCESS_DENIED.getMessage(),
                        traceId
                ));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResult<?>> handleNotFound(HttpServletRequest request, Exception e) {
        String traceId = newTraceId();
        return ResponseEntity.status(NOT_FOUND)
                .body(ApiResult.error(
                        CommonErrorCode.RESOURCE_NOT_FOUND.getCode(),
                        CommonErrorCode.RESOURCE_NOT_FOUND.getMessageKey(),
                        CommonErrorCode.RESOURCE_NOT_FOUND.format(request.getRequestURI()),
                        traceId
                ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        String traceId = newTraceId();
        return ResponseEntity.status(METHOD_NOT_ALLOWED)
                .body(ApiResult.error(
                        CommonErrorCode.METHOD_NOT_ALLOWED.getCode(),
                        CommonErrorCode.METHOD_NOT_ALLOWED.getMessageKey(),
                        CommonErrorCode.METHOD_NOT_ALLOWED.getMessage(),
                        traceId
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<?>> handleNotReadable(HttpMessageNotReadableException e) {
        String traceId = newTraceId();
        Throwable root = e.getMostSpecificCause();
        String messageKey = ErrorMessage.Validation.REQUEST_FORMAT_INVALID_KEY;
        String message = ErrorMessage.Validation.REQUEST_FORMAT_INVALID.defaultMessage();
        Object details = null;

        if (root instanceof InvalidFormatException invalidFormatException) {
            String field = invalidFormatException.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .collect(Collectors.joining("."));
            boolean numeric = Number.class.isAssignableFrom(invalidFormatException.getTargetType())
                    || invalidFormatException.getTargetType().isPrimitive();
            ErrorMessage.Template template = numeric
                    ? ErrorMessage.Validation.FIELD_RANGE_INVALID
                    : ErrorMessage.Validation.FIELD_FORMAT_INVALID;
            messageKey = template.key();
            message = template.format(field);
            details = Map.of(field, new ValidationFailure(messageKey, message));
        } else if (root instanceof StreamReadException) {
            messageKey = ErrorMessage.Validation.JSON_MALFORMED_KEY;
            message = ErrorMessage.Validation.JSON_MALFORMED.defaultMessage();
        }

        log.warn("Unreadable request [{}]: {}", traceId, root.getMessage());
        return ResponseEntity.status(BAD_REQUEST)
                .body(ApiResult.error(
                        CommonErrorCode.INVALID_REQUEST.getCode(),
                        messageKey,
                        message,
                        traceId,
                        details
                ));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<?>> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
        String traceId = newTraceId();
        log.warn("Optimistic locking conflict [{}]: {}", traceId, ex.getMessage());
        return ResponseEntity.status(CommonErrorCode.RESOURCE_CONFLICT.getHttpStatus())
                .body(ApiResult.error(
                        CommonErrorCode.RESOURCE_CONFLICT.getCode(),
                        CommonErrorCode.RESOURCE_CONFLICT.getMessageKey(),
                        CommonErrorCode.RESOURCE_CONFLICT.getMessage(),
                        traceId
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResult<?>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String traceId = newTraceId();
        String rootMsg = ex.getRootCause() != null ? ex.getRootCause().getMessage() : "";
        log.error("{} | Data integrity error [{}]: {}", LogConstant.SYS_ERROR, traceId, rootMsg);
        return ResponseEntity.status(CommonErrorCode.DATA_INTEGRITY_ERROR.getHttpStatus())
                .body(ApiResult.error(
                        CommonErrorCode.DATA_INTEGRITY_ERROR.getCode(),
                        CommonErrorCode.DATA_INTEGRITY_ERROR.getMessageKey(),
                        CommonErrorCode.DATA_INTEGRITY_ERROR.getMessage(),
                        traceId
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<?>> handleGlobal(Exception e) {
        String traceId = newTraceId();
        log.error("Internal server error [{}]", traceId, e);
        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(
                        CommonErrorCode.INTERNAL_ERROR.getCode(),
                        CommonErrorCode.INTERNAL_ERROR.getMessageKey(),
                        CommonErrorCode.INTERNAL_ERROR.getMessage(),
                        traceId
                ));
    }

    private ValidationFailure toValidationFailure(String rawMessage) {
        String messageKey = ErrorMessage.stripBeanValidationBraces(rawMessage);
        String message = ErrorMessage.defaultMessage(messageKey);
        if (message.equals(messageKey)) {
            messageKey = ErrorMessage.Validation.INVALID_INPUT_KEY;
            message = hasText(rawMessage) ? rawMessage : ErrorMessage.Validation.INVALID_INPUT.defaultMessage();
        }
        return new ValidationFailure(messageKey, message);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String newTraceId() {
        return UUID.randomUUID().toString();
    }

    private record ValidationFailure(String messageKey, String message) {
    }
}
