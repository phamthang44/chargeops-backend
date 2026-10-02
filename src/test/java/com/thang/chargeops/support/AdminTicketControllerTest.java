package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.support.controller.AdminTicketController;
import com.thang.chargeops.support.service.AdminTicketService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminTicketController.class)
@Import({GlobalHandlerError.class, AdminTicketControllerTest.SecurityConfig.class})
class AdminTicketControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AdminTicketService service;
    @MockitoBean TicketEventQueryService events;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanGetPlatformQueue() throws Exception {
        when(service.platformQueue(any(), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/tickets")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk());

        verify(service).platformQueue(any(), eq(1), eq(20));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanGetEscalatedStationCases() throws Exception {
        when(service.stationAudit(any(), any(), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/tickets/escalated")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk());

        verify(service).stationAudit(any(), any(), eq(1), eq(20));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCannotBrowseStationHandlers() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/admin/tickets/" + ticketId + "/station-handlers"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAssignTicket() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();
        when(service.assign(eq(ticketId), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/admin/tickets/" + ticketId + "/assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"handlerId\":\"" + handlerId + "\",\"reason\":\"Admin routing\"}"))
                .andExpect(status().isOk());

        verify(service).assign(eq(ticketId), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReply() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(service.reply(eq(ticketId), any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/admin/tickets/" + ticketId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Admin note\"}"))
                .andExpect(status().isCreated());

        verify(service).reply(eq(ticketId), any(), any());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tickets"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
