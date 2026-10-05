package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.station.staff.service.StaffStationOperationService;

import com.thang.chargeops.booking.service.StationOperationalBookingService;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.station.staff.controller.StaffStationOperationController;
import com.thang.chargeops.station.staff.dto.StaffOperationResponses;
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

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffStationOperationController.class)
@Import(StaffStationOperationControllerSecurityTest.SecurityConfig.class)
class StaffStationOperationControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean StaffStationOperationService operations;
    @MockitoBean StationOperationalBookingService bookings;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCannotUseStaffConsole() throws Exception {
        UUID station = UUID.randomUUID();
        UUID point = UUID.randomUUID();
        UUID connector = UUID.randomUUID();
        String base = "/api/v1/staff/stations/" + station;

        mvc.perform(get(base)).andExpect(status().isForbidden());
        mvc.perform(get(base + "/bookings")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/charge-points")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/charge-points/" + point + "/status-history"))
                .andExpect(status().isForbidden());
        mvc.perform(get(base + "/charge-points/" + point + "/connectors"))
                .andExpect(status().isForbidden());
        mvc.perform(get(base + "/charge-points/" + point + "/connectors/" + connector + "/status-history"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(base + "/charge-points/" + point + "/operational-status")
                .contentType("application/json")
                .content("{\"operationalStatus\":\"OFFLINE\",\"expectedVersion\":0,\"reason\":\"Inspect\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(base + "/charge-points/" + point + "/connectors/" + connector + "/runtime-status")
                .contentType("application/json")
                .content("{\"runtimeStatus\":\"OFFLINE\",\"expectedVersion\":0,\"reason\":\"Inspect\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(operations, bookings);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffOverviewContainsOnlyOperationalFieldsAndDisablesCaching() throws Exception {
        UUID station = UUID.randomUUID();
        when(operations.overview(station)).thenReturn(new StaffOperationResponses.Overview(
                station, "Station A", "Address A",
                com.thang.chargeops.common.enums.StationOperationalStatus.OPERATING, 2, 4));

        mvc.perform(get("/api/v1/staff/stations/{stationId}", station))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.stationId").value(station.toString()))
                .andExpect(jsonPath("$.data.chargePointCount").value(2))
                .andExpect(jsonPath("$.data.revenue").doesNotExist())
                .andExpect(jsonPath("$.data.ownerId").doesNotExist());
    }
}
