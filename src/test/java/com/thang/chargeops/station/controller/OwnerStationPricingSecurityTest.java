package com.thang.chargeops.station.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.service.StationConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerStationPricingController.class)
@Import({
        GlobalHandlerError.class,
        OwnerStationPricingSecurityTest.MethodSecurityTestConfig.class
})
class OwnerStationPricingSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StationConfigurationService stationConfigurationService;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    void controllerEnforcesOwnerRoleAtClassLevel() {
        PreAuthorize preAuthorize = OwnerStationPricingController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('OWNER')");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCallingGetPricingIsRejectedWithForbidden() throws Exception {
        UUID stationId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/pricing", stationId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCallingUpdatePricingIsRejectedWithForbidden() throws Exception {
        UUID stationId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/owner/stations/{stationId}/pricing", stationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "minBookingDurationMin": 30,
                                  "basePriceVnd": 3500.0,
                                  "open24Hours": true,
                                  "hours": [
                                    {"day": "MONDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true},
                                    {"day": "TUESDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true},
                                    {"day": "WEDNESDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true},
                                    {"day": "THURSDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true},
                                    {"day": "FRIDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true},
                                    {"day": "SATURDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true},
                                    {"day": "SUNDAY", "openTime": "06:00:00", "closeTime": "23:00:00", "enabled": true}
                                  ],
                                  "touRules": [],
                                  "version": 1
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCallingGetScheduleHistoryIsRejectedWithForbidden() throws Exception {
        UUID stationId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/pricing/schedule-history", stationId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCallingGetPricingIsRejectedWithForbidden() throws Exception {
        UUID stationId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/pricing", stationId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void ownerCallingGetPricingIsPermitted() throws Exception {
        UUID stationId = UUID.randomUUID();
        StationPricingResponse mockResponse = new StationPricingResponse(
                stationId,
                30,
                30,
                180,
                new BigDecimal("3500.00"),
                true,
                List.of(),
                List.of(),
                new StationPricingResponse.AvailabilityPolicyResponse(true, 15, 2, 10),
                null,
                null,
                "ACTIVE",
                1L
        );
        when(stationConfigurationService.getConfiguration(eq(stationId)))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/pricing", stationId))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        UUID stationId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/pricing", stationId))
                .andExpect(status().isForbidden());
    }
}
