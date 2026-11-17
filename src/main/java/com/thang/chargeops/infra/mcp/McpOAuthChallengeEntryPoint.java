package com.thang.chargeops.infra.mcp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

public class McpOAuthChallengeEntryPoint implements AuthenticationEntryPoint {

    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpOAuthChallengeEntryPoint(McpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + properties.metadataUrl()
                + "\", scope=\"" + properties.getReadScope() + "\"");
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", "unauthorized"));
    }
}
