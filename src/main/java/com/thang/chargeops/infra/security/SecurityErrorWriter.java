package com.thang.chargeops.infra.security;


import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/**
 * Writes the standard {@link ApiResult} error envelope for security-layer
 * failures (401/403), keeping the contract consistent with {@code GlobalHandlerError}.
 */
final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    static void write(ObjectMapper objectMapper, HttpServletRequest request,
                      HttpServletResponse response, BaseErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResult<?> body = ApiResult.error(
                errorCode.getCode(),
                errorCode.getMessageKey(),
                errorCode.format(),
                UUID.randomUUID().toString()
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
