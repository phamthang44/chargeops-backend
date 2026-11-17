package com.thang.chargeops.infra.mcp;

import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.infra.security.abuse.AbuseGroup;
import com.thang.chargeops.infra.security.abuse.AbuseProperties;
import com.thang.chargeops.infra.security.abuse.AbuseRateLimiter;
import com.thang.chargeops.infra.security.abuse.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class McpRateLimitFilter extends OncePerRequestFilter {

    private final AbuseRateLimiter limiter;
    private final ClientIpResolver ipResolver;
    private final ObjectMapper objectMapper;

    public McpRateLimitFilter(AbuseRateLimiter limiter, ClientIpResolver ipResolver,
                              ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.ipResolver = ipResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication) || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        AbuseRateLimiter.Decision decision = limiter.acquire(AbuseGroup.MCP_READ,
                authentication.getToken().getSubject(), ipResolver.resolve(request));
        if (decision.allowed() || limiter.mode() != AbuseProperties.Mode.ENFORCE) {
            filterChain.doFilter(request, response);
            return;
        }

        String traceId = UUID.randomUUID().toString();
        response.setStatus("SEC_003".equals(decision.code())
                ? HttpServletResponse.SC_SERVICE_UNAVAILABLE : 429);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        objectMapper.writeValue(response.getOutputStream(), ApiResult.error(decision.code(),
                "error.security." + decision.code(), "Please wait before trying this action again.",
                traceId, Map.of("group", AbuseGroup.MCP_READ.name(),
                        "retryAfterSeconds", decision.retryAfterSeconds())));
    }
}
