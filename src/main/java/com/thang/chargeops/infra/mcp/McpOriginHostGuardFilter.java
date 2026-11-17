package com.thang.chargeops.infra.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

public class McpOriginHostGuardFilter extends OncePerRequestFilter {

    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpOriginHostGuardFilter(McpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.acceptsHost(request.getServerName(), request.getServerPort())
                || !properties.acceptsOrigin(request.getHeader("Origin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            objectMapper.writeValue(response.getOutputStream(), Map.of("error", "invalid_origin_or_host"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
