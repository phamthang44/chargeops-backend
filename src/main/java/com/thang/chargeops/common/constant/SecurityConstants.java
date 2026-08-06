package com.thang.chargeops.common.constant;

/**
 * Security-related constants shared across the security infrastructure.
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** Endpoints that do not require authentication. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error",
            // Actuator (health/info/prometheus) — Prometheus scrapes without a token.
            // In prod keep these off the public Nginx route; Prometheus reaches them on the internal network.
            "/actuator/**"
    };

    public static String[] publicEndpoints() {
        return PUBLIC_ENDPOINTS.clone();
    }

    /** Authority prefix Spring Security expects for {@code hasRole(...)} checks. */
    public static final String ROLE_PREFIX = "ROLE_";
}
