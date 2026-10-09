package com.thang.chargeops.profile;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.profile.controller.AdminUserController;
import com.thang.chargeops.profile.service.AdminUserQueryService;
import org.junit.jupiter.api.Test;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@Import({GlobalHandlerError.class, AdminUserControllerTest.SecurityConfig.class})
class AdminUserControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AdminUserQueryService adminUsers;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @org.springframework.boot.test.context.TestConfiguration
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
    void adminListsUsersPaginatedWithoutCaching() throws Exception {
        when(adminUsers.list(eq(1), eq(20), isNull(), isNull(), isNull()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listRejectsOversizedPageSize() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminUsers);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminUsers);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminReadsSummaryAndDetail() throws Exception {
        when(adminUsers.summary(isNull(), isNull(), isNull()))
                .thenReturn(com.thang.chargeops.profile.dto.AdminUserSummaryResponse.builder().build());
        mockMvc.perform(get("/api/v1/admin/users/summary"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        var profileId = java.util.UUID.randomUUID();
        when(adminUsers.detail(profileId)).thenReturn(
                com.thang.chargeops.profile.dto.AdminUserProfileResponse.builder().build());
        mockMvc.perform(get("/api/v1/admin/users/" + profileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownRoleParamRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("role", "SUPERUSER"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminUsers);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusParamAcceptsCanonicalEnum() throws Exception {
        when(adminUsers.list(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mockMvc.perform(get("/api/v1/admin/users").param("status", "SUSPENDED"))
                .andExpect(status().isOk());
    }
}
