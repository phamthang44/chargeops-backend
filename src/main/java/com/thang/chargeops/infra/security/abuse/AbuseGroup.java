package com.thang.chargeops.infra.security.abuse;

/** An exact method/route allowlist; ongoing work never shares a creation bucket. */
public enum AbuseGroup {
    BOOKING_CREATE(5), TICKET_CREATE(5), TICKET_MESSAGE(20), EXPORT(2), ASSISTANT(3), WS_SUBSCRIBE(30);
    public final int perMinute;
    AbuseGroup(int perMinute) { this.perMinute = perMinute; }

    public static AbuseGroup match(String method, String path) {
        if (path == null) return null;
        try {
            // MVC matches decoded path segments (e.g. %62ookings). Match the same spelling.
            path = org.springframework.web.util.UriUtils.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if ("GET".equals(method) && "/api/v1/owner/bookings/export".equals(path)) return EXPORT;
        if (!"POST".equals(method)) return null;
        if ("/api/v1/bookings".equals(path)) return BOOKING_CREATE;
        if ("/api/v1/tickets".equals(path)) return TICKET_CREATE;
        if ("/api/v1/assistant/ask".equals(path)) return ASSISTANT;
        if (path.matches("/api/v1/(?:owner/|admin/)?tickets/[0-9a-fA-F-]{36}/messages")) return TICKET_MESSAGE;
        return null;
    }
}
