package com.thang.chargeops.common.constant;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.Locale;

/**
 * Security-related constants shared across the security infrastructure.
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Endpoints that do not require authentication. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error",
            "/api/v1/administrative-units/**",
            "/api/v1/legal-documents/**",
            "/api/v1/legal-documents/terms-of-service",
            // WebSocket handshake is intentionally unauthenticated: browsers cannot send
            // Authorization headers during the HTTP upgrade. Real authentication happens at
            // the STOMP CONNECT frame (StompAuthChannelInterceptor) which fails closed.
            "/ws",
            "/ws/**",
            // Actuator (health/info/prometheus) — Prometheus scrapes without a token.
            // In prod keep these off the public Nginx route; Prometheus reaches them on the internal network.
            "/actuator/**"
    };

    public static String[] publicEndpoints() {
        return PUBLIC_ENDPOINTS.clone();
    }

    /**
     * UM-02: single source for "request needs no authenticated actor", mirroring the
     * permitAll matchers in {@code SecurityConfig} (method-agnostic list + GET stations +
     * POST sepay webhook). The account-status gate uses this so a suspended/deleted profile
     * is only blocked on non-public (method, path) pairs — never on public reads.
     */
    public static boolean isPublicRequest(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        for (String pattern : PUBLIC_ENDPOINTS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        boolean isGet = HttpMethod.GET.matches(normalizedMethod);
        boolean isPost = HttpMethod.POST.matches(normalizedMethod);
        if (isGet && (PATH_MATCHER.match("/api/v1/stations", path)
                || PATH_MATCHER.match("/api/v1/stations/**", path))) {
            return true;
        }
        return isPost && PATH_MATCHER.match("/api/v1/webhooks/sepay", path);
    }

    /** Authority prefix Spring Security expects for {@code hasRole(...)} checks. */
    public static final String ROLE_PREFIX = "ROLE_";
}
