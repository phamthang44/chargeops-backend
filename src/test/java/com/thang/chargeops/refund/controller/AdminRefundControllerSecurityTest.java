package com.thang.chargeops.refund.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.refund.service.AdminRefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminRefundController.class)
@Import({GlobalHandlerError.class, AdminRefundControllerSecurityTest.MethodSecurityTestConfig.class})
class AdminRefundControllerSecurityTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AdminRefundService adminRefundService;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    @Test
    void nonAdminsCannotReadOrExecuteRefunds() throws Exception {
        for (String role : new String[]{"DRIVER", "OWNER", "STAFF"}) {
            UUID refundId = UUID.randomUUID();
            mockMvc.perform(get("/api/v1/admin/refunds").with(user("user").roles(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/admin/refunds/{refundId}", refundId)
                            .with(user("user").roles(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(executeRequest(refundId).with(user("user").roles(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminRefundService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReachExecuteValidationBoundary() throws Exception {
        mockMvc.perform(executeRequest(UUID.randomUUID())).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder executeRequest(UUID refundId) {
        return post("/api/v1/admin/refunds/{refundId}/execute", refundId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("""
                        {
                          "expectedVersion": 0,
                          "executionMode": "SIMULATOR",
                          "outcome": "FAILED",
                          "note": "Security contract"
                        }
                        """);
    }
}

