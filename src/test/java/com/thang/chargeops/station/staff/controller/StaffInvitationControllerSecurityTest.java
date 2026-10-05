package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.station.staff.dto.StaffInvitationResponse;
import com.thang.chargeops.station.staff.entity.StaffInvitationStatus;
import com.thang.chargeops.station.staff.service.StaffInvitationService;

import com.thang.chargeops.exception.GlobalHandlerError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerStaffInvitationController.class)
@Import({GlobalHandlerError.class, StaffInvitationControllerSecurityTest.SecurityConfig.class})
class StaffInvitationControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean StaffInvitationService service;

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
    void driverCannotInviteStaff() throws Exception {
        mvc.perform(post("/api/v1/owner/stations/{stationId}/staff-invitations", UUID.randomUUID())
                        .contentType("application/json").content("{\"email\":\"staff@example.com\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void ownerCanInviteThroughScopedEndpoint() throws Exception {
        UUID stationId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        when(service.invite(eq(stationId), eq("staff@example.com"))).thenReturn(
                new StaffInvitationResponse(invitationId, stationId, "staff@example.com",
                        StaffInvitationStatus.SENT, Instant.parse("2026-10-12T04:00:00Z"),
                        Instant.parse("2026-10-05T04:00:00Z"), null));

        mvc.perform(post("/api/v1/owner/stations/{stationId}/staff-invitations", stationId)
                        .contentType("application/json").content("{\"email\":\"staff@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", CacheControl.noStore().getHeaderValue()))
                .andExpect(jsonPath("$.data.status").value("SENT"));
    }
}
