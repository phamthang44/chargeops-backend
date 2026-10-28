package com.thang.chargeops.infra.security.abuse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AbuseRateLimitFilterTest {
    @Test void observePassesThroughEvenWhenStoreWouldReject() throws Exception {
        var limiter = mock(AbuseRateLimiter.class); var resolver = mock(ClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn("192.0.2.1");
        when(limiter.mode()).thenReturn(AbuseProperties.Mode.OBSERVE);
        when(limiter.acquire(any(), any(), any())).thenReturn(new AbuseRateLimiter.Decision("SEC_003", 5, "store"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("fixture").header("alg", "RS256").subject("actor").build(), List.of()));
        var chain = mock(FilterChain.class);
        new AbuseRateLimitFilter(limiter, resolver, new ObjectMapper()).doFilter(
                new MockHttpServletRequest("POST", "/api/v1/bookings"), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void blocksBeforeControllerWithStandardContract() throws Exception {
        var limiter = mock(AbuseRateLimiter.class); var resolver = mock(ClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn("192.0.2.1");
        when(limiter.mode()).thenReturn(AbuseProperties.Mode.ENFORCE);
        when(limiter.acquire(any(), eq("subject"), any())).thenReturn(new AbuseRateLimiter.Decision("SEC_002", 900, "subject"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("test").header("alg", "RS256").subject("subject").build(), List.of()));
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        new AbuseRateLimitFilter(limiter, resolver, new ObjectMapper()).doFilter(
                new MockHttpServletRequest("POST", "/api/v1/bookings"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(response.getContentAsString()).contains("SEC_002", "BOOKING_CREATE", "retryAfterSeconds");
        verifyNoInteractions(chain);
    }
    @Test void noAuthenticatedSubjectNoPenaltyAndNoHeaderIdentity() throws Exception {
        var limiter = mock(AbuseRateLimiter.class); var chain = mock(FilterChain.class);
        var request = new MockHttpServletRequest("POST", "/api/v1/bookings");
        request.addHeader("Authorization", "Bearer unverified");
        new AbuseRateLimitFilter(limiter, mock(ClientIpResolver.class), new ObjectMapper())
                .doFilter(request, new MockHttpServletResponse(), chain);
        verifyNoInteractions(limiter); verify(chain).doFilter(any(), any());
    }
    @Test void essentialRoutesNeverShareCreationOrMessageBuckets() {
        assertThat(AbuseGroup.match("POST", "/api/v1/%62ookings")).isEqualTo(AbuseGroup.BOOKING_CREATE);
        assertThat(AbuseGroup.match("GET", "/api/v1/owner/bookings/%65xport")).isEqualTo(AbuseGroup.EXPORT);
        for (String path : List.of("/api/v1/bookings/id/checkout", "/api/v1/bookings/id/cancel",
                "/api/v1/bookings/id/check-in", "/api/v1/bookings/id/start-charging",
                "/api/v1/bookings/id/complete", "/api/v1/webhooks/sepay", "/api/v1/tickets/id/claim"))
            assertThat(AbuseGroup.match("POST", path)).as(path).isNull();
        assertThat(AbuseGroup.match("GET", "/api/v1/bookings")).isNull();
        for (String prefix : List.of("", "owner/", "admin/"))
            assertThat(AbuseGroup.match("POST", "/api/v1/" + prefix + "tickets/12345678-1234-1234-1234-123456789012/messages"))
                    .isEqualTo(AbuseGroup.TICKET_MESSAGE);
    }

    @Test void handlesNullOrMalformedPathGracefully() {
        assertThat(AbuseGroup.match("POST", null)).isNull();
        assertThat(AbuseGroup.match("POST", "/api/v1/bookings%FF")).isNull();
        assertThat(AbuseGroup.match("POST", "/api/v1/bookings%1")).isNull();
    }
}
