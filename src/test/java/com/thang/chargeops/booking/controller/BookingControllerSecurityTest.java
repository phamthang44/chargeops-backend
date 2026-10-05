package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.booking.service.BookingSessionService;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@Import({
        GlobalHandlerError.class,
        BookingControllerSecurityTest.MethodSecurityTestConfig.class
})
class BookingControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingPricingService bookingPricingService;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private BookingSessionService bookingSessionService;

    @MockitoBean
    private com.thang.chargeops.booking.service.BookingCancellationService bookingCancellationService;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth
                            .anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void nonDriverCannotCallAnyDriverBookingReadEndpoint()
            throws Exception {
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/bookings/active"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/bookings/history"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingId))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/bookings/stats"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/{bookingId}/checkout", bookingId)
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/{bookingId}/cancel", bookingId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 1,
                                  "expectedRefundAmount": 0,
                                  "acceptedPolicyVersion": "booking-v4.9"
                                }
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/check-in/resolve")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": "%s",
                                  "challengeToken": "challenge-token-1234567890"
                                }
                                """.formatted(bookingId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/{bookingId}/check-in", bookingId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 1,
                                  "challengeToken": "challenge-token-1234567890"
                                }
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/{bookingId}/start-charging", bookingId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/{bookingId}/complete", bookingId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 1}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(
                bookingService,
                bookingSessionService,
                bookingCancellationService
        );
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCanCallCancelEndpoint() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/bookings/{bookingId}/cancel", bookingId)
                        .header("Idempotency-Key", requestKey)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 1,
                                  "expectedRefundAmount": 0,
                                  "acceptedPolicyVersion": "booking-v4.9"
                                }
                                """))
                .andExpect(status().isOk());
    }
}
