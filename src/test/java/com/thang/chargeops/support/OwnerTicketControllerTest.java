package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.support.controller.OwnerTicketController;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.service.OwnerTicketService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerTicketController.class)
@Import({GlobalHandlerError.class, AdminTicketControllerTest.SecurityConfig.class})
class OwnerTicketControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean OwnerTicketService service;
    @MockitoBean TicketEventQueryService events;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @WithMockUser(roles = "OWNER")
    void ownerCanPollMessagesForOwnedTicket() throws Exception {
        UUID ticketId = UUID.randomUUID();
        var message = new TicketMessageResponse(UUID.randomUUID(), "Driver", TicketActorKind.REPORTER,
                "Trạm chưa phản hồi", Instant.parse("2026-10-02T12:00:00Z"));
        var ticket = new TicketResponse(ticketId, "TKT-001", TicketCategory.CHARGING_ISSUE,
                TicketPriority.MEDIUM, "Sự cố trạm", TicketStatus.IN_PROGRESS, 1L,
                null, UUID.randomUUID(), UUID.randomUUID(), null, Instant.parse("2026-10-02T11:00:00Z"),
                List.of(message), List.of(), List.of());
        when(service.get(ticketId)).thenReturn(ticket);

        mockMvc.perform(get("/api/v1/owner/tickets/" + ticketId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].body").value("Trạm chưa phản hồi"));

        verify(service).get(ticketId);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCannotReadOwnerMessagesRoute() throws Exception {
        mockMvc.perform(get("/api/v1/owner/tickets/" + UUID.randomUUID() + "/messages"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
