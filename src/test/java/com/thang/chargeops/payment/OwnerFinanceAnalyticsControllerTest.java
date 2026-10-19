package com.thang.chargeops.payment;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.payment.controller.OwnerFinanceAnalyticsController;
import com.thang.chargeops.payment.dto.response.OwnerFinanceAnalyticsResponse;
import com.thang.chargeops.payment.service.OwnerFinanceAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OwnerFinanceAnalyticsController.class)
@ActiveProfiles("test")
@Import({GlobalHandlerError.class, OwnerFinanceAnalyticsControllerTest.Config.class})
class OwnerFinanceAnalyticsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnerFinanceAnalyticsService reports;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;
    @TestConfiguration @EnableMethodSecurity
    static class Config {
        @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }
    @Test void ownerReceivesNoStoreSimulatorReportWithServerOwnedScope() throws Exception {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        when(reports.analytics(null, "7d")).thenReturn(new OwnerFinanceAnalyticsResponse(
                "SIMULATOR", "VND", "Asia/Ho_Chi_Minh", "7d", null, now, now, now,
                0, 100, -100, List.of(), List.of(), 0, 10));
        mvc.perform(get("/api/v1/owner/finance/analytics").param("ownerId", UUID.randomUUID().toString())
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.environment").value("SIMULATOR"))
                .andExpect(jsonPath("$.data.netVnd").value(-100));
        verify(reports).analytics(null, "7d");
    }
    @Test void propagatesStationAndPeriod() throws Exception {
        UUID station = UUID.randomUUID();
        mvc.perform(get("/api/v1/owner/finance/analytics").param("stationId", station.toString()).param("period", "30d")
                .with(user("owner").roles("OWNER"))).andExpect(status().isOk());
        verify(reports).analytics(station, "30d");
    }
    @Test void otherRolesCannotReadOwnerFinance() throws Exception {
        for (String role : new String[]{"ADMIN", "STAFF", "DRIVER"}) {
            mvc.perform(get("/api/v1/owner/finance/analytics").with(user("other").roles(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(reports);
    }
    @Test void malformedStationIdIsRejected() throws Exception {
        mvc.perform(get("/api/v1/owner/finance/analytics").param("stationId", "invalid")
                .with(user("owner").roles("OWNER"))).andExpect(status().isBadRequest());
        verifyNoInteractions(reports);
    }
}
