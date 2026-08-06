package com.thang.chargeops.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

/**
 * Standard response envelope for every API endpoint.
 *
 * <p>A response is one of two shapes (never both — {@code null} fields are omitted
 * from JSON via {@link JsonInclude}):
 * <ul>
 *   <li><b>Success</b> → {@link #data} (+ {@link #meta}). Built with the {@code success(...)} factories.</li>
 *   <li><b>Error</b> → {@link #error}. Built with the {@code error(...)} factories.</li>
 * </ul>
 *
 * <p>Use the static factories rather than the builder directly — pick the one whose
 * signature matches your case (plain value, paginated list, error, etc.).
 *
 * @param <T> type of the success payload
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> {

    /** The success payload. Null on error responses. */
    private T data;

    /** Envelope metadata: server time, message, pagination, sort/filter. Null on error responses. */
    private Meta meta;

    /** Error details. Null on success responses. */
    private ErrorDetail error;

    // ─────────────────────────────────────────────────────────────────────────
    // Inner types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Metadata attached to success responses. All fields are optional and omitted
     * from JSON when null, so a simple response only carries the auto-filled
     * system fields. Pagination fields come in two mutually exclusive flavours:
     * <b>cursor</b> ({@code nextCursor}/{@code hasNextPage}) or
     * <b>offset</b> ({@code page}/{@code size}/{@code totalElements}/{@code totalPages}).
     */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        // --- System info (auto-filled) ---
        /** Server epoch millis when the response was built (auto-filled). */
        @Builder.Default
        private Long serverTime = System.currentTimeMillis();

        /** API version tag (auto-filled). */
        @Builder.Default
        private String apiVersion = "1.0.0";

        /** Correlation id for this response (auto-filled); useful for tracing/support. */
        @Builder.Default
        private String traceId = java.util.UUID.randomUUID().toString();

        // --- UI feedback ---
        /** Optional short message for the client to surface (e.g. "Booking confirmed"). */
        private String message;

        // --- Cursor pagination (feeds / infinite scroll) ---
        /** Opaque cursor pointing at the item after the last one returned; null when no more pages. */
        private String nextCursor;
        /** Whether another page exists after this one. */
        private Boolean hasNextPage;

        // --- Offset pagination (admin tables) ---
        /** Zero-based page index. */
        private Integer page;
        /** Page size. */
        private Integer size;
        /** Total number of matching elements across all pages. */
        private Long totalElements;
        /** Total number of pages. */
        private Integer totalPages;

        // --- Sort & filter (echoed back for the client) ---
        /** Applied sort expression, echoed back to the client. */
        private String sort;
        /** Applied filters, echoed back to the client. */
        private Map<String, Object> filter;
    }

    /**
     * Error payload. The {@link #code} is the stable contract the frontend
     * localizes by; {@link #message} is a fixed English default for logs/fallback.
     */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        /** Stable, machine-readable error code (e.g. {@code BOOKING_001}). */
        private String code;
        /** Stable frontend i18n key (e.g. {@code error.auth.accessDenied}). */
        private String messageKey;
        /** English default message — for logs/debugging; clients localize by {@code code}. */
        private String message;
        /** Correlation id, mirrored in logs for support. */
        private String traceId;
        /** Optional structured extras, e.g. field→message validation map or interpolation params. */
        private Object details;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Success factories
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Plain success carrying a single payload (object, DTO, or list) with no message.
     * Use for the common GET-one / GET-all-small case.
     *
     * @param data the payload to return
     */
    public static <T> ApiResult<T> success(T data) {
        return ApiResult.<T>builder()
                .data(data)
                .meta(Meta.builder().build()) // serverTime auto-filled via @Builder.Default
                .build();
    }

    /**
     * Success with a payload <b>and</b> a short message for the client to surface
     * (e.g. a toast like "Booking confirmed"). Use after a successful mutation.
     *
     * @param data    the payload to return
     * @param message UI feedback message
     */
    public static <T> ApiResult<T> success(T data, String message) {
        return ApiResult.<T>builder()
                .data(data)
                .meta(Meta.builder().message(message).build())
                .build();
    }

    /**
     * Success with a fully custom {@link Meta}. Use when you need to set fields the
     * other factories don't cover — e.g. echoing {@code sort}/{@code filter} back, or
     * a hand-built pagination/metadata combination.
     *
     * @param data the payload to return
     * @param meta pre-built metadata
     */
    public static <T> ApiResult<T> success(T data, Meta meta) {
        return ApiResult.<T>builder()
                .data(data)
                .meta(meta)
                .build();
    }

    /**
     * Success for <b>cursor pagination</b> (feeds / infinite scroll). Returns the page
     * of items plus the cursor to fetch the next page.
     *
     * @param data       the page of items
     * @param nextCursor opaque cursor for the next page (null if none)
     * @param hasNext    whether a further page exists
     */
    public static <T> ApiResult<T> success(T data, String nextCursor, boolean hasNext) {
        return ApiResult.<T>builder()
                .data(data)
                .meta(Meta.builder()
                        .nextCursor(nextCursor)
                        .hasNextPage(hasNext)
                        .build())
                .build();
    }

    /**
     * Success for <b>offset pagination</b> built from raw values. Use when you
     * computed paging yourself (not from a Spring Data {@link Page}).
     * {@code totalPages} is derived as {@code ceil(total / size)}.
     *
     * @param items the page of items
     * @param page  zero-based page index
     * @param size  page size
     * @param total total matching elements across all pages
     */
    public static <T> ApiResult<List<T>> success(List<T> items, int page, int size, long total) {
        return ApiResult.<List<T>>builder()
                .data(items)
                .meta(Meta.builder()
                        .page(page)
                        .size(size)
                        .totalElements(total)
                        .totalPages((int) Math.ceil((double) total / size))
                        .build())
                .build();
    }

    /**
     * Success for <b>offset pagination</b> directly from a Spring Data {@link Page}
     * (the entity/DTO type in the page is what's returned as-is). Use when the
     * repository already returns the shape you want to expose.
     *
     * @param page the Spring Data page
     */
    public static <T> ApiResult<List<T>> successPage(Page<T> page) {
        return success(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * Like {@link #successPage(Page)} but also attaches a UI message.
     *
     * @param page    the Spring Data page
     * @param message UI feedback message
     */
    public static <T> ApiResult<List<T>> successPage(Page<T> page, String message) {
        return ApiResult.<List<T>>builder()
                .data(page.getContent())
                .meta(Meta.builder()
                        .message(message)
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .build())
                .build();
    }

    /**
     * Success for <b>offset pagination</b> when you've mapped entities to a different
     * response type: pass the already-mapped {@code content} plus the original
     * {@link Page} for its paging info. The common "map {@code Page<Entity>} →
     * {@code List<Dto>}, keep the page numbers" case.
     *
     * @param content  the mapped items to return
     * @param pageInfo the source page providing paging metadata
     * @param <R>      mapped response type
     * @param <T>      source entity type
     */
    public static <R, T> ApiResult<List<R>> success(List<R> content, Page<T> pageInfo) {
        return ApiResult.<List<R>>builder()
                .data(content)
                .meta(Meta.builder()
                        .page(pageInfo.getNumber())
                        .size(pageInfo.getSize())
                        .totalElements(pageInfo.getTotalElements())
                        .totalPages(pageInfo.getTotalPages())
                        .build())
                .build();
    }

    /**
     * Empty success — no payload. Use for actions that succeed but return nothing
     * (e.g. delete, state transition) when you still want the standard envelope.
     */
    public static <T> ApiResult<T> success() {
        return ApiResult.<T>builder()
                .meta(Meta.builder().build())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error factories
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Basic error. Prefer {@link #error(BaseErrorCode, Object...)} when you have an
     * error-code enum; use this overload when the caller already has the parts
     * (e.g. the global exception handler that owns its own trace id).
     *
     * @param code    stable error code
     * @param message English default message
     * @param traceId correlation id
     */
    public static ApiResult<?> error(String code, String message, String traceId) {
        return error(code, null, message, traceId);
    }

    public static ApiResult<?> error(String code, String messageKey, String message, String traceId) {
        return ApiResult.builder()
                .error(ErrorDetail.builder()
                        .code(code)
                        .messageKey(messageKey)
                        .message(message)
                        .traceId(traceId)
                        .build())
                .build();
    }

    /**
     * Error with structured {@link ErrorDetail#details} — typically a
     * field→message validation map, or params the frontend interpolates into its
     * own localized string.
     *
     * @param code    stable error code
     * @param message English default message
     * @param traceId correlation id
     * @param details structured extras (validation map, params, ...)
     */
    public static ApiResult<?> error(String code, String message, String traceId, Object details) {
        return error(code, null, message, traceId, details);
    }

    public static ApiResult<?> error(String code, String messageKey, String message, String traceId, Object details) {
        return ApiResult.builder()
                .error(ErrorDetail.builder()
                        .code(code)
                        .messageKey(messageKey)
                        .message(message)
                        .traceId(traceId)
                        .details(details)
                        .build())
                .build();
    }

    /**
     * Error built straight from a {@link BaseErrorCode}. Pulls the code and the
     * English default message from the enum (formatting in any {@code args}), and
     * generates a fresh trace id. The most convenient error factory for services.
     *
     * @param errorCode the error code (null → generic UNKNOWN_ERROR)
     * @param args      optional values substituted into the message's {@code {0}, {1}, ...} placeholders
     */
    public static ApiResult<?> error(BaseErrorCode errorCode, Object... args) {
        String message = errorCode != null ? errorCode.format(args) : ErrorMessage.Common.UNKNOWN_ERROR.defaultMessage();
        String code = errorCode != null ? errorCode.getCode() : "UNKNOWN_ERROR";
        String messageKey = errorCode != null ? errorCode.getMessageKey() : null;
        String traceId = java.util.UUID.randomUUID().toString();
        return ApiResult.builder()
                .error(ErrorDetail.builder()
                        .code(code)
                        .messageKey(messageKey)
                        .message(message)
                        .traceId(traceId)
                        .build())
                .build();
    }
}
