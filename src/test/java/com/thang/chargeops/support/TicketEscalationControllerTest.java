package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.support.controller.TicketEscalationController;
import com.thang.chargeops.support.service.TicketEscalationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketEscalationController.class)
@Import({GlobalHandlerError.class, SupportTicketControllerTest.SecurityConfig.class})
class TicketEscalationControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean TicketEscalationService service;

    @Test
    @WithMockUser(roles = "DRIVER")
    void absentEscalationIsSuccessfulNullData() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(service.getDetail(ticketId)).thenReturn(null);

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/escalation", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void presentEscalationIncludesRequestedByRole() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        var detail = new com.thang.chargeops.support.dto.response.TicketEscalationDetailResponse(
                ticketId, driverId, java.time.Instant.now(), "Trạm không hỗ trợ", "driver");
        when(service.getDetail(ticketId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/escalation", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedByRole").value("driver"))
                .andExpect(jsonPath("$.data.reason").value("Trạm không hỗ trợ"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReturnActiveEscalationWithReviewNote() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/admin/tickets/{ticketId}/escalation", ticketId)
                .contentType("application/json")
                .content("{\"expectedVersion\":1,\"action\":\"RETURN_TO_STATION\",\"note\":\"Station should continue investigating\"}"))
            .andExpect(status().isOk());
        verify(service).review(eq(ticketId), any());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void reporterCannotDecideAdministrativeReview() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tickets/{ticketId}/escalation", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"expectedVersion\":1,\"action\":\"CLOSE_SUPPORT_CASE\",\"closureReason\":\"OTHER\",\"note\":\"No next step\"}"))
            .andExpect(status().isForbidden());
    }
}
