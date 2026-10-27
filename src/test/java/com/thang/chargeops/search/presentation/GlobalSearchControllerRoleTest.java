package com.thang.chargeops.search.presentation;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.search.GlobalSearchService;
import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.dto.GlobalSearchResponse;
import com.thang.chargeops.search.dto.SearchGroup;
import com.thang.chargeops.search.dto.SearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ranh giới vai trò + validation của GET /api/v1/search (không cần DB). */
@WebMvcTest(GlobalSearchController.class)
@Import({GlobalHandlerError.class, GlobalSearchControllerRoleTest.SecurityConfig.class})
class GlobalSearchControllerRoleTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean GlobalSearchService globalSearchService;

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
    void anonymousAndDriverAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "global"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/search").param("q", "global").with(user("driver").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffOwnerAdminCanSearchAndSeeEnvelope() throws Exception {
        when(globalSearchService.search(anyString(), any(), anyInt()))
                .thenReturn(new GlobalSearchResponse("global", List.of(
                        new SearchGroup(GlobalSearchType.STATION, List.of(
                                new SearchHit(UUID.randomUUID(), "Global Alpha Station",
                                        "1 Alpha Road", "GS-ALPHA"))))));
        for (String role : List.of("STAFF", "OWNER", "ADMIN")) {
            mockMvc.perform(get("/api/v1/search").param("q", "global").with(user(role).roles(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groups[0].type").value("STATION"))
                    .andExpect(jsonPath("$.data.groups[0].items[0].title").value("Global Alpha Station"))
                    .andExpect(jsonPath("$.data.groups[0].items[0].badge").value("GS-ALPHA"));
        }
    }

    @Test
    void shortKeywordIsBadRequest() throws Exception {
        when(globalSearchService.search(eq("g"), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("q must be at least 2 characters"));
        mockMvc.perform(get("/api/v1/search").param("q", "g").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void longKeywordExceedingMaxLengthIsBadRequest() throws Exception {
        String longQuery = "a".repeat(101);
        mockMvc.perform(get("/api/v1/search").param("q", longQuery).with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitIsBoundedAndTypeIsOnlyAFilter() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "global").param("limit", "0")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search").param("q", "global").param("limit", "99")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());

        when(globalSearchService.search(eq("global"), eq(GlobalSearchType.CHARGER), eq(5)))
                .thenReturn(new GlobalSearchResponse("global", List.of()));
        mockMvc.perform(get("/api/v1/search").param("q", "global").param("type", "CHARGER")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        verify(globalSearchService).search("global", GlobalSearchType.CHARGER, 5);
    }
}
