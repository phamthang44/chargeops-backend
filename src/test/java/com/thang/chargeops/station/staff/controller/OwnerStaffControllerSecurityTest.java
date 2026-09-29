package com.thang.chargeops.station.staff.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.station.staff.dto.StationStaffResponse;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.service.StationStaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerStaffController.class)
@Import({GlobalHandlerError.class, OwnerStaffControllerSecurityTest.SecurityConfig.class})
class OwnerStaffControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private StationStaffService stationStaffService;
    @MockitoBean private RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean private RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    void listOwnerStaff_whenUnauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/owner/staffs"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(stationStaffService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void listOwnerStaff_whenDriver_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/owner/staffs"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(stationStaffService);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void listOwnerStaff_whenOwner_returns200() throws Exception {
        StationStaffResponse staff = StationStaffResponse.builder()
                .assignmentId(UUID.randomUUID())
                .stationId(UUID.randomUUID())
                .stationName("Trạm Cầu Giấy")
                .userId(UUID.randomUUID())
                .email("staff@chargeops.vn")
                .displayName("Staff Name")
                .assignedAt(Instant.now())
                .status(StaffAssignmentStatus.ACTIVE)
                .build();

        when(stationStaffService.listAllOwnerStaff(any(), anyInt(), anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(staff)));

        mockMvc.perform(get("/api/v1/owner/staffs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stationName").value("Trạm Cầu Giấy"))
                .andExpect(jsonPath("$.data[0].email").value("staff@chargeops.vn"));
    }
}
