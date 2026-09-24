package com.thang.chargeops.payment.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.payment.dto.request.SimulationRequest;
import com.thang.chargeops.payment.dto.response.SimulationResultResponse;
import com.thang.chargeops.payment.service.PaymentSimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(PaymentSimulatorController.class)
@Import({
        GlobalHandlerError.class,
        PaymentSimulatorControllerSecurityTest.MethodSecurityTestConfig.class
})
class PaymentSimulatorControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentSimulationService paymentSimulationService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                            .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                    .build();
        }
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCannotSimulatePayment() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();

        mockMvc.perform(simulationRequest(bookingId, requestKey))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentSimulationService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanSimulatePayment() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        when(paymentSimulationService.simulate(eq(bookingId), eq(requestKey), any()))
                .thenReturn(new SimulationResultResponse(
                        SimulationRequest.Outcome.SUCCESS,
                        false,
                        null,
                        null
                ));

        mockMvc.perform(simulationRequest(bookingId, requestKey))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedCannotSimulatePayment() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();

        mockMvc.perform(simulationRequest(bookingId, requestKey))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentSimulationService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder simulationRequest(
            UUID bookingId,
            UUID requestKey
    ) {
        return post("/api/v1/bookings/{bookingId}/simulate-payment", bookingId)
                .header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "transactionRef": "TX-SECURITY-001",
                          "outcome": "SUCCESS",
                          "amount": 100000,
                          "currency": "VND",
                          "providerPaidAt": "2026-09-21T10:00:00Z"
                        }
                        """);
    }
}
