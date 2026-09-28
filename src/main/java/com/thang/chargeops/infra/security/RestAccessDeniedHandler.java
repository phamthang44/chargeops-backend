package com.thang.chargeops.infra.security;


import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Handles requests from authenticated users who lack the required authority
 * (e.g. a DRIVER calling an OWNER/ADMIN endpoint — BR-ACC-03). Returns a 403
 * with the standard {@code ErrorResponse} body.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_FAILED, "handle",
                request.getUserPrincipal() == null ? "ANONYMOUS" : "AUTHENTICATED",
                "httpMethod=" + request.getMethod() + ", path=" + request.getRequestURI());
        SecurityErrorWriter.write(objectMapper, request, response, AuthErrorCode.ACCESS_DENIED);
    }
}
