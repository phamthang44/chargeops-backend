package com.thang.chargeops.refund.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.payment.controller.OwnerFinanceController;
import com.thang.chargeops.payment.dto.response.OwnerFinanceSummaryResponse;
import com.thang.chargeops.payment.service.OwnerFinanceService;
import com.thang.chargeops.refund.dto.response.OwnerRefundsSummaryResponse;
import com.thang.chargeops.refund.service.OwnerRefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest({OwnerRefundController.class, OwnerFinanceController.class})
@Import({GlobalHandlerError.class, RefundRoleBoundaryControllerTest.SecurityConfig.class})
class RefundRoleBoundaryControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean OwnerRefundService ownerRefundService;
    @MockitoBean OwnerFinanceService ownerFinanceService;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    void adminHasNoGlobalRefundRoute() throws Exception {
        UUID refundId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/admin/refunds").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/refunds/{id}/execute", refundId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerFinanceAndRefundRoutesRejectAdminAndDriver() throws Exception {
        for (String role : new String[]{"ADMIN", "DRIVER", "STAFF"}) {
            mockMvc.perform(get("/api/v1/owner/refunds").with(user(role).roles(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/owner/finance/bookings").with(user(role).roles(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void ownerCanReachOnlyOwnerScopedListRoutes() throws Exception {
        when(ownerRefundService.list(isNull(), eq(1), eq(20))).thenReturn(Page.empty());
        when(ownerFinanceService.list(1, 20)).thenReturn(Page.empty());
        mockMvc.perform(get("/api/v1/owner/refunds").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/owner/finance/bookings").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void ownerSummariesAreScopedRoutesWithDedicatedPayloads() throws Exception {
        when(ownerFinanceService.summary()).thenReturn(
                new OwnerFinanceSummaryResponse(1000, 200, 800, 100, 3, 2));
        when(ownerRefundService.summary()).thenReturn(
                new OwnerRefundsSummaryResponse(1, 2, 1, 1, 300, 100));
        mockMvc.perform(get("/api/v1/owner/finance/summary").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netVnd").value(800));
        mockMvc.perform(get("/api/v1/owner/refunds/summary").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresOwnerActionCount").value(1));
        for (String role : new String[]{"ADMIN", "DRIVER", "STAFF"}) {
            mockMvc.perform(get("/api/v1/owner/finance/summary").with(user(role).roles(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/owner/refunds/summary").with(user(role).roles(role)))
                    .andExpect(status().isForbidden());
        }
    }
}
