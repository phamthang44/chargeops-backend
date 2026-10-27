package com.thang.chargeops.infra.security.abuse;

import com.thang.chargeops.common.response.ApiResult;
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

/** Registered only in the security chain, never as a second servlet-container filter. */
public class AbuseRateLimitFilter extends OncePerRequestFilter {
    private final AbuseRateLimiter limiter;
    private final ClientIpResolver ipResolver;
    private final ObjectMapper mapper;
    private com.thang.chargeops.infra.persistence.CurrentActorContext actors;
    public AbuseRateLimitFilter(AbuseRateLimiter limiter, ClientIpResolver resolver, ObjectMapper mapper) {
        this.limiter = limiter; this.ipResolver = resolver; this.mapper = mapper;
    }
    public AbuseRateLimitFilter(AbuseRateLimiter limiter, ClientIpResolver resolver, ObjectMapper mapper,
                               com.thang.chargeops.infra.persistence.CurrentActorContext actors) {
        this(limiter, resolver, mapper); this.actors = actors;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AbuseGroup group = AbuseGroup.match(request.getMethod(), request.getRequestURI());
        if (group == null || !(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()) {
            chain.doFilter(request, response); return;
        }
        var decision = limiter.acquire(group, auth.getToken().getSubject(), ipResolver.resolve(request));
        if (decision.allowed()) { chain.doFilter(request, response); return; }
        String traceId = UUID.randomUUID().toString();
        if (actors != null) actors.get().ifPresent(profile -> org.slf4j.LoggerFactory.getLogger(AbuseRateLimitFilter.class)
                .warn("security_abuse_profile profileContext={} trace={} group={} decision={} scope={} subjectEvidence={} mode={}",
                        profile, traceId, group, decision.code(), decision.scope(), "subject".equals(decision.scope()), limiter.mode()));
        if (limiter.mode() != AbuseProperties.Mode.ENFORCE) { chain.doFilter(request, response); return; }
        response.setStatus("SEC_003".equals(decision.code()) ? 503 : 429);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        mapper.writeValue(response.getOutputStream(), ApiResult.error(decision.code(),
                "error.security." + decision.code(), "Please wait before trying this action again.",
                traceId, Map.of("group", group.name(),
                        "retryAfterSeconds", decision.retryAfterSeconds())));
    }
}
