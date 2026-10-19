package com.thang.chargeops.moderation;

import com.thang.chargeops.exception.*;
import com.thang.chargeops.exception.errorcode.ModerationErrorCode;
import com.thang.chargeops.infra.security.*;
import com.thang.chargeops.moderation.controller.AdminProhibitedTermController;
import com.thang.chargeops.moderation.dto.*;
import com.thang.chargeops.moderation.model.ContentScope;
import com.thang.chargeops.moderation.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminProhibitedTermController.class)
@Import({GlobalHandlerError.class, AdminProhibitedTermControllerTest.SecurityConfig.class})
class AdminProhibitedTermControllerTest {
    private static final String URL = "/api/v1/admin/prohibited-terms";
    @Autowired MockMvc mvc;
    @MockitoBean ProhibitedTermAdminService admin;
    @MockitoBean ContentModerationService moderation;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }
    @Test void adminListUsesExistingEnvelopeAndPaging() throws Exception {
        when(admin.list(isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mvc.perform(get(URL).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.page").value(1)).andExpect(jsonPath("$.meta.size").value(20));
    }
    @ParameterizedTest @ValueSource(strings = {"DRIVER", "OWNER", "STAFF"})
    void nonAdminsCannotUseAnyManagementOperation(String role) throws Exception {
        var actor = user("user").roles(role);
        String id = UUID.randomUUID().toString();
        String body = "{\"term\":\"bad\",\"matchType\":\"EXACT\",\"scopes\":[\"DISPLAY_NAME\"],\"enabled\":true,\"version\":0}";
        mvc.perform(get(URL).with(actor)).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/" + id).with(actor)).andExpect(status().isForbidden());
        mvc.perform(post(URL).with(actor).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put(URL + "/" + id).with(actor).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(patch(URL + "/" + id + "/status").with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":0}")).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/" + id + "/history").with(actor)).andExpect(status().isForbidden());
        mvc.perform(post(URL + "/check").with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"DISPLAY_NAME\",\"text\":\"bad\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(admin, moderation);
    }
    @Test void malformedRulesAndPageSizesAreRejected() throws Exception {
        mvc.perform(post(URL).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"term\":\"\",\"matchType\":\"EXACT\",\"scopes\":[],\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("size", "101").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/" + UUID.randomUUID()).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(admin);
    }
    @Test void adminCanCreateReadUpdateChangeStatusAndReadHistory() throws Exception {
        UUID id = UUID.randomUUID();
        var term = new ProhibitedTermResponse(id, "bad", "bad", com.thang.chargeops.moderation.model.TermMatchType.EXACT,
                Set.of(ContentScope.DISPLAY_NAME), false, null, 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH, null, null);
        when(admin.create(any())).thenReturn(term);
        when(admin.get(id)).thenReturn(term);
        when(admin.update(eq(id), any())).thenReturn(term);
        when(admin.setStatus(eq(id), any())).thenReturn(term);
        when(admin.history(id, 1, 20)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        String body = "{\"term\":\"bad\",\"matchType\":\"EXACT\",\"scopes\":[\"DISPLAY_NAME\"],\"enabled\":false}";
        mvc.perform(post(URL).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id.toString()));
        mvc.perform(get(URL + "/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(put(URL + "/" + id).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content(body.substring(0, body.length() - 1) + ",\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(patch(URL + "/" + id + "/status").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(get(URL + "/" + id + "/history").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.page").value(1));
    }
    @Test void previewHasNoWriteAndUnavailableUsesStandardError() throws Exception {
        when(moderation.check(ContentScope.DISPLAY_NAME, "clean")).thenReturn(new ModerationCheckResponse(true, List.of()));
        mvc.perform(post(URL + "/check").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"DISPLAY_NAME\",\"text\":\"clean\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed").value(true));
        when(moderation.check(ContentScope.DISPLAY_NAME, "clean")).thenThrow(new AppException(ModerationErrorCode.MODERATION_UNAVAILABLE));
        mvc.perform(post(URL + "/check").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"DISPLAY_NAME\",\"text\":\"clean\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MODERATION_UNAVAILABLE"));
        verifyNoInteractions(admin);
    }
}
