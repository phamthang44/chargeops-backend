package com.thang.chargeops.exception;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.ValidationErrorMessage;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.LicenseErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.exception.ChargePointDomainException;
import com.thang.chargeops.station.exception.ConnectorDomainException;
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
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

    @ExceptionHandler(ChargePointDomainException.class)
    public ResponseEntity<ApiResult<?>> handleChargePointDomainException(ChargePointDomainException e) {
        BaseErrorCode errorCode = switch (e.getViolation()) {
            case STATION_REQUIRED -> StationErrorCode.STATION_NOT_FOUND;
            case CODE_REQUIRED -> StationErrorCode.CHARGE_POINT_CODE_REQUIRED;
            case MAX_POWER_OUT_OF_RANGE -> StationErrorCode.CHARGE_POINT_MAX_POWER_RANGE_INVALID;
            case CONNECTOR_POWER_EXCEEDS_MAX_POWER -> StationErrorCode.CONNECTOR_POWER_EXCEEDS_MAX_POWER;
            case STATUS_REQUIRED -> StationErrorCode.CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED;
            case INVALID_PROVISIONING_TRANSITION -> StationErrorCode.INVALID_CHARGE_POINT_PROVISIONING_TRANSITION;
        };
        return handleAppException(new AppException(errorCode, e.getMessage()));
    }

    @ExceptionHandler(ConnectorDomainException.class)
    public ResponseEntity<ApiResult<?>> handleConnectorDomainException(ConnectorDomainException e) {
        BaseErrorCode errorCode = switch (e.getViolation()) {
            case CODE_REQUIRED -> StationErrorCode.CONNECTOR_CODE_REQUIRED;
            case POWER_OUT_OF_RANGE -> StationErrorCode.CONNECTOR_POWER_RANGE_INVALID;
            case TYPE_MISMATCH -> StationErrorCode.CONNECTOR_TYPE_MISMATCH;
            case POWER_EXCEEDS_MAX_POWER -> StationErrorCode.CONNECTOR_POWER_EXCEEDS_MAX_POWER;
            case TYPE_REQUIRED -> CommonErrorCode.INVALID_REQUEST;
            case STATUS_REQUIRED -> StationErrorCode.CONNECTOR_RUNTIME_STATUS_REQUIRED;
        };
        return handleAppException(new AppException(errorCode, e.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResult<?>> handleValidationException(Exception e) {
        String traceId = newTraceId();
        String messageKey = ValidationErrorMessage.FAILED_KEY;
        String message = ValidationErrorMessage.FAILED.defaultMessage();
        Object details = null;

        if (e instanceof MethodArgumentNotValidException ex) {
            BindingResult result = ex.getBindingResult();
            Map<String, ValidationFailure> errors = new HashMap<>();
            for (FieldError fieldError : result.getFieldErrors()) {
                errors.put(fieldError.getField(), toValidationFailure(fieldError.getDefaultMessage()));
            }
            details = errors;
        } else if (e instanceof HandlerMethodValidationException ex) {
            details = toValidationDetails(ex);
        } else if (e instanceof ConstraintViolationException ex) {
            details = ex.getConstraintViolations().stream()
                    .collect(Collectors.toMap(
                            violation -> violation.getPropertyPath().toString(),
                            violation -> toValidationFailure(violation.getMessage()),
                            (left, right) -> left));
        } else if (e instanceof MissingServletRequestParameterException ex) {
            messageKey = ValidationErrorMessage.REQUIRED_PARAMETER_KEY;
            message = ValidationErrorMessage.REQUIRED_PARAMETER.format(ex.getParameterName());
            details = Map.of(ex.getParameterName(), new ValidationFailure(messageKey, message));
        } else if (e instanceof IllegalArgumentException ex) {
            messageKey = ValidationErrorMessage.INVALID_INPUT_KEY;
            message = hasText(ex.getMessage()) ? ex.getMessage() : ValidationErrorMessage.INVALID_INPUT.defaultMessage();
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
        String messageKey = ValidationErrorMessage.REQUEST_FORMAT_INVALID_KEY;
        String message = ValidationErrorMessage.REQUEST_FORMAT_INVALID.defaultMessage();
        Object details = null;

        if (root instanceof InvalidFormatException invalidFormatException) {
            String field = invalidFormatException.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .collect(Collectors.joining("."));
            boolean numeric = Number.class.isAssignableFrom(invalidFormatException.getTargetType())
                    || invalidFormatException.getTargetType().isPrimitive();
            ErrorMessage.Template template = numeric
                    ? ValidationErrorMessage.FIELD_RANGE_INVALID
                    : ValidationErrorMessage.FIELD_FORMAT_INVALID;
            messageKey = template.key();
            message = template.format(field);
            details = Map.of(field, new ValidationFailure(messageKey, message));
        } else if (root instanceof StreamReadException) {
            messageKey = ValidationErrorMessage.JSON_MALFORMED_KEY;
            message = ValidationErrorMessage.JSON_MALFORMED.defaultMessage();
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
        BaseErrorCode errorCode = isLicenseConflict(ex)
                ? LicenseErrorCode.LICENSE_WAS_MODIFIED
                : CommonErrorCode.RESOURCE_CONFLICT;
        log.warn("Optimistic locking conflict [{}]: {}", traceId, ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResult.error(
                        errorCode.getCode(),
                        errorCode.getMessageKey(),
                        errorCode.getMessage(),
                        traceId
                ));
    }

    private boolean isLicenseConflict(ObjectOptimisticLockingFailureException ex) {
        Class<?> persistentClass = ex.getPersistentClass();
        return persistentClass != null && License.class.isAssignableFrom(persistentClass);
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

    /**
     * VI: Gom lỗi validation method của Spring 7 về map field → chi tiết giống
     * lỗi DTO thông thường, để frontend không phải xử lý hai JSON contract.
     *
     * <p>EN: Converts Spring 7 method-validation results into the same
     * field-to-detail map used for regular DTO validation, keeping one JSON
     * contract for the frontend.</p>
     */
    private Map<String, ValidationFailure> toValidationDetails(
            HandlerMethodValidationException exception
    ) {
        Map<String, ValidationFailure> errors = new HashMap<>();

        exception.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError fieldError : parameterErrors.getFieldErrors()) {
                    errors.put(
                            fieldError.getField(),
                            toValidationFailure(fieldError.getDefaultMessage())
                    );
                }
                return;
            }

            String parameterName = result.getMethodParameter().getParameterName();
            String field = parameterName != null
                    ? parameterName
                    : "argument" + result.getMethodParameter().getParameterIndex();

            result.getResolvableErrors().forEach(error ->
                    errors.put(field, toValidationFailure(error.getDefaultMessage()))
            );
        });

        return errors;
    }

    private ValidationFailure toValidationFailure(String rawMessage) {
        String messageKey = ErrorMessage.stripBeanValidationBraces(rawMessage);
        String message = ErrorMessage.defaultMessage(messageKey);
        if (message.equals(messageKey)) {
            messageKey = ValidationErrorMessage.INVALID_INPUT_KEY;
            message = hasText(rawMessage) ? rawMessage : ValidationErrorMessage.INVALID_INPUT.defaultMessage();
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
