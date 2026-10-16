package com.thang.chargeops.station.controller;

import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;
import com.thang.chargeops.station.service.StationService;
import com.thang.chargeops.station.service.OwnerDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OwnerOperationsSummaryController.class)
@Import(OwnerOperationsSummaryControllerTest.SecurityConfig.class)
class OwnerOperationsSummaryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean StationService service;
    @MockitoBean OwnerDashboardService ownerDashboardService;

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void exposesDedicatedCardsAndDashboardWithoutCaching() throws Exception {
        OwnerOperationsSummaryResponse summary = new OwnerOperationsSummaryResponse(Instant.EPOCH,
                LocalDate.of(2026, 10, 6), "Asia/Ho_Chi_Minh",
                new OwnerOperationsSummaryResponse.Stations(11, 8, 5, 1, 30, 30),
                new OwnerOperationsSummaryResponse.Hardware(19, 19, 0, 0, 0, 0, BigDecimal.ZERO));
        when(service.getOwnerOperationsSummary()).thenReturn(summary);
        when(ownerDashboardService.summary()).thenReturn(summary);
        mvc.perform(get("/api/v1/owner/stations/summary")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.visibleToDrivers").value(5));
        mvc.perform(get("/api/v1/owner/charge-points/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalConnectors").value(19));
        mvc.perform(get("/api/v1/owner/dashboard/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stations.totalStations").value(11))
                .andExpect(jsonPath("$.data.hardware.sessionsToday").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stations", "charge-points", "dashboard"})
    @WithMockUser(roles = {"DRIVER", "STAFF", "ADMIN"})
    void otherRolesCannotReadOwnerSummary(String screen) throws Exception {
        mvc.perform(get("/api/v1/owner/" + screen + "/summary")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
