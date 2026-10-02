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
        when(service.get(ticketId)).thenReturn(null);

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/escalation", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
