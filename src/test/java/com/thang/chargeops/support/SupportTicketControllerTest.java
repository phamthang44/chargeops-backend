package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.support.controller.SupportTicketController;
import com.thang.chargeops.support.service.SupportTicketService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupportTicketController.class)
@Import({GlobalHandlerError.class, SupportTicketControllerTest.SecurityConfig.class})
class SupportTicketControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean SupportTicketService ticketService;
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
        when(ticketService.getTickets(any(), any(), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/tickets")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk());

        verify(ticketService).getTickets(any(), any(), eq(1), eq(20));
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
        when(ticketService.getTicket(ticketId)).thenReturn(null);

        mockMvc.perform(get("/api/v1/tickets/" + ticketId))
                .andExpect(status().isOk());

        verify(ticketService).getTicket(ticketId);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void authenticatedReplyTicketWithIdempotencyHeaderReturnsCreated() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/messages")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"Thank you for checking this."}
                                """))
                .andExpect(status().isCreated());

        verify(ticketService).replyTicket(eq(ticketId), eq(idempotencyKey), any());
    }
}
