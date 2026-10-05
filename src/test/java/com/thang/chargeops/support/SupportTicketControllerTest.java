package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.support.controller.SupportTicketController;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationAvailabilityResponse;
import com.thang.chargeops.support.service.SupportTicketService;
import com.thang.chargeops.support.service.TicketFindingService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(SupportTicketController.class)
@Import({GlobalHandlerError.class, SupportTicketControllerTest.SecurityConfig.class})
class SupportTicketControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean SupportTicketService ticketService;
    @MockitoBean TicketWorkflowService workflowService;
    @MockitoBean TicketEventQueryService ticketReadService;
    @MockitoBean TicketFindingService findings;

    @Test
    @WithMockUser(roles = "STAFF")
    void findingEndpointRequiresValidatedEvidence() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/tickets/{ticketId}/findings", ticketId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(findings);
    }
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
    @WithMockUser(roles = "DRIVER")
    void claimRequiresExpectedVersion() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/tickets/{ticketId}/claim", ticketId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(workflowService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void statusEndpointDelegatesValidatedTransition() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/tickets/{ticketId}/status", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"status\":\"CLOSED\"}"))
            .andExpect(status().isOk());
        verify(workflowService).changeStatus(eq(ticketId), any());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void findingEndpointDelegatesToFindingServiceWhenValid() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(findings.record(eq(ticketId), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/tickets/{ticketId}/findings", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":1,"conclusion":"STATION_FAILURE",
                     "affectedAt":"2026-10-02T10:00:00Z","reason":"Hardware failure verified"}
                    """))
            .andExpect(status().isCreated());
        verify(findings).record(eq(ticketId), any());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void claimDelegatesWhenValid() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(workflowService.claim(eq(ticketId), eq(0L))).thenReturn(null);

        mockMvc.perform(post("/api/v1/tickets/{ticketId}/claim", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
            .andExpect(status().isOk());
        verify(workflowService).claim(eq(ticketId), eq(0L));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void assignDelegatesWhenValid() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();
        when(workflowService.assign(eq(ticketId), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/tickets/{ticketId}/assignment", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"handlerId\":\"" + handlerId + "\",\"reason\":\"Assign technician\"}"))
            .andExpect(status().isOk());
        verify(workflowService).assign(eq(ticketId), any());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void assignRejectsInvalidPayload() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/tickets/{ticketId}/assignment", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"handlerId\":\"" + UUID.randomUUID() + "\",\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(workflowService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void authenticatedCreateNeedsNoIdempotencyHeader() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"PAYMENT","priority":"MEDIUM",
                                 "subject":"Receipt issue","description":"Payment needs review"}
                                """))
                .andExpect(status().isCreated());
        verify(ticketService).create(any());
    }

    @Test
    void anonymousCreateIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"PAYMENT","priority":"MEDIUM",
                                 "subject":"Receipt issue","description":"Payment needs review"}
                                """))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ticketService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void authenticatedGetTicketsReturnsOk() throws Exception {
        when(ticketService.getTickets(any(), any(), eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/tickets")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk());

        verify(ticketService).getTickets(any(), any(), eq(1), eq(20), any());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void getTicketsWithReporterScopeIsPassedToService() throws Exception {
        when(ticketService.getTickets(any(), any(), eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/tickets")
                        .param("page", "1")
                        .param("size", "20")
                        .param("scope", "REPORTER"))
                .andExpect(status().isOk());

        verify(ticketService).getTickets(any(), any(), eq(1), eq(20),
                eq(com.thang.chargeops.support.model.TicketListScope.REPORTER));
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void getTicketsWithInvalidScopeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/tickets")
                        .param("scope", "bogus"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ticketService);
    }

    @Test
    void anonymousGetTicketsIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/tickets"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ticketService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void authenticatedGetTicketReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        var detail = new TicketDetailResponse(
                new TicketDetailResponse.Overview(ticketId, "TKT-1", null, null, "Help", null,
                        0L, "Details", null, null),
                null, null, null, null, null, false, null,
                new TicketEscalationAvailabilityResponse(false, null,
                        TicketEscalationAvailabilityResponse.Reason.WAITING_FOR_STATION));
        when(ticketService.getTicketDetail(eq(ticketId), any())).thenReturn(detail);

        mockMvc.perform(get("/api/v1/tickets/" + ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overview.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.data.isEscalated").value(false))
                .andExpect(jsonPath("$.data.escalation").value(nullValue()));

        verify(ticketService).getTicketDetail(eq(ticketId), any());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void getTicketDetailWithReporterScopeIsPassedToService() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getTicketDetail(eq(ticketId), any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/tickets/" + ticketId)
                        .param("scope", "REPORTER"))
                .andExpect(status().isOk());

        verify(ticketService).getTicketDetail(eq(ticketId),
                eq(com.thang.chargeops.support.model.TicketListScope.REPORTER));
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void getTicketDetailWithInvalidScopeIsBadRequest() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/tickets/" + ticketId)
                        .param("scope", "bogus"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ticketService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void authenticatedReplyTicketWithOptionalClientMessageIdReturnsCreated() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/messages")
                        .header("Client-Message-Id", clientMessageId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"Thank you for checking this."}
                                """))
                .andExpect(status().isCreated());

        verify(ticketService).replyTicket(eq(ticketId), eq(clientMessageId), any());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void replyTicketWithoutClientKeyIsAllowed() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"No client key\"}"))
                .andExpect(status().isCreated());

        verify(ticketService).replyTicket(eq(ticketId), eq(null), any());
    }
}
