package com.thang.chargeops.infra.security;


import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Handles requests to protected endpoints that carry no/invalid authentication.
 * Returns a 401 with the standard {@code ErrorResponse} body instead of the
 * default empty response.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        SecurityErrorWriter.write(objectMapper, request, response, AuthErrorCode.UNAUTHENTICATED);
    }
}
