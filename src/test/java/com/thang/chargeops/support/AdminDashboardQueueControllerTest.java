package com.thang.chargeops.support;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.support.controller.AdminDashboardQueueController;
import com.thang.chargeops.support.service.AdminDashboardQueueService;
import com.thang.chargeops.support.dto.response.AdminDashboardQueuePage;
import com.thang.chargeops.support.model.AdminDashboardQueueScope;
import com.thang.chargeops.support.model.AdminDashboardSort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDashboardQueueController.class)
@Import({GlobalHandlerError.class, AdminDashboardQueueControllerTest.SecurityConfig.class})
class AdminDashboardQueueControllerTest {
    static final Instant GENERATED_AT = Instant.parse("2026-10-16T08:00:00Z");
    @Autowired MockMvc mockMvc;
    @MockitoBean AdminDashboardQueueService queues;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(
                            (request, response, exception) -> response.sendError(401)))
                    .build();
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminReadsPaginatedPlatformQueueWithoutCaching() throws Exception {
        when(queues.platform(1, 5, AdminDashboardSort.NEWEST_FIRST))
                .thenReturn(empty(AdminDashboardQueueScope.PLATFORM_TICKETS, AdminDashboardSort.NEWEST_FIRST));
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/platform"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.generatedAt").value(GENERATED_AT.toString()))
                .andExpect(jsonPath("$.meta.scope").value("PLATFORM_TICKETS"))
                .andExpect(jsonPath("$.meta.sort").value("NEWEST_FIRST"))
                .andExpect(jsonPath("$.data").isArray());
        verify(queues).platform(1, 5, AdminDashboardSort.NEWEST_FIRST);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotReadEscalatedQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/escalated"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(queues);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void approvalsDefaultToOldestAndEscalatedKeepsLegacyDefault() throws Exception {
        when(queues.approvals(1, 5, AdminDashboardSort.OLDEST_FIRST))
                .thenReturn(empty(AdminDashboardQueueScope.PENDING_APPROVALS, AdminDashboardSort.OLDEST_FIRST));
        when(queues.escalated(1, 5, AdminDashboardSort.NEWEST_FIRST))
                .thenReturn(empty(AdminDashboardQueueScope.ESCALATED_CASES, AdminDashboardSort.NEWEST_FIRST));
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/approvals"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.scope").value("PENDING_APPROVALS"))
                .andExpect(jsonPath("$.meta.sort").value("OLDEST_FIRST"));
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/escalated"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.sort").value("NEWEST_FIRST"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void explicitSortAndPageArePassedToService() throws Exception {
        when(queues.platform(2, 10, AdminDashboardSort.OLDEST_FIRST))
                .thenReturn(new AdminDashboardQueuePage<>(new PageImpl<>(List.of(), PageRequest.of(1, 10), 10),
                        GENERATED_AT, AdminDashboardQueueScope.PLATFORM_TICKETS, AdminDashboardSort.OLDEST_FIRST));
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/platform")
                        .param("page", "2").param("size", "10").param("sort", "OLDEST_FIRST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.size").value(10))
                .andExpect(jsonPath("$.meta.totalElements").value(10))
                .andExpect(jsonPath("$.meta.sort").value("OLDEST_FIRST"));
        verify(queues).platform(2, 10, AdminDashboardSort.OLDEST_FIRST);
    }

    @ParameterizedTest
    @CsvSource({"approvals,page,0", "platform,size,0", "escalated,size,101", "platform,sort,createdAt",
            "approvals,sort,UNKNOWN", "escalated,page,abc"})
    @WithMockUser(roles = "ADMIN")
    void invalidQueryParametersReturn400(String queue, String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/" + queue).param(parameter, value))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(queues);
    }

    @ParameterizedTest
    @CsvSource({"approvals", "platform", "escalated"})
    @WithMockUser(roles = "OWNER")
    void ownerCannotReadAnyQueue(String queue) throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/" + queue)).andExpect(status().isForbidden());
        verifyNoInteractions(queues);
    }

    @Test
    void anonymousCannotReadApprovals() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/queues/approvals")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queues);
    }

    private static <T> AdminDashboardQueuePage<T> empty(AdminDashboardQueueScope scope, AdminDashboardSort sort) {
        return new AdminDashboardQueuePage<>(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0), GENERATED_AT, scope, sort);
    }
}
