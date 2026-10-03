package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.support.controller.AdminOperationsSummaryController;
import com.thang.chargeops.support.controller.AdminTicketController;
import com.thang.chargeops.support.dto.response.AdminOperationsSummaryResponse;
import com.thang.chargeops.support.dto.response.AdminTicketSummaryResponse;
import com.thang.chargeops.support.service.AdminOperationsSummaryService;
import com.thang.chargeops.support.service.AdminTicketService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminOperationsSummaryController.class, AdminTicketController.class})
@Import({GlobalHandlerError.class, AdminTicketControllerTest.SecurityConfig.class})
class AdminOperationsSummaryControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AdminOperationsSummaryService summaries;
    @MockitoBean AdminTicketService tickets;
    @MockitoBean TicketEventQueryService events;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSummaryRoutesReturnDedicatedData() throws Exception {
        when(summaries.dashboard()).thenReturn(new AdminOperationsSummaryResponse(12, 3, 2, 1));
        when(summaries.tickets(false)).thenReturn(new AdminTicketSummaryResponse(5,
                Map.of("open", 2L, "in_progress", 1L, "resolved", 1L, "closed", 1L), 2, 1, 1, 1));
        when(summaries.tickets(true)).thenReturn(new AdminTicketSummaryResponse(2,
                Map.of("open", 1L, "in_progress", 1L, "resolved", 0L, "closed", 0L), 1, 1, 0, 0));

        mockMvc.perform(get("/api/v1/admin/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingApprovals").value(3));
        mockMvc.perform(get("/api/v1/admin/tickets/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byStatus.open").value(2));
        mockMvc.perform(get("/api/v1/admin/tickets/escalated/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byStatus.in_progress").value(1));
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void nonAdminCannotReadOperationalSummaries() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/summary")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/tickets/summary")).andExpect(status().isForbidden());
    }
}
