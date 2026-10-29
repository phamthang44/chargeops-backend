package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import com.thang.chargeops.station.dto.station.response.StationAmenitiesResponse;
import com.thang.chargeops.station.service.StationAmenitiesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerStationAmenitiesController.class)
@Import({GlobalHandlerError.class, OwnerStationAmenitiesControllerTest.MethodSecurityTestConfig.class})
class OwnerStationAmenitiesControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private StationAmenitiesService stationAmenitiesService;
    @MockitoBean private RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean private RestAccessDeniedHandler accessDeniedHandler;

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
    void controllerEnforcesOwnerRoleAtClassLevel() {
        PreAuthorize preAuthorize = OwnerStationAmenitiesController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('OWNER')");
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void ownerReadsAmenitiesIncludingEmptyArray() throws Exception {
        UUID stationId = UUID.randomUUID();
        when(stationAmenitiesService.getOwnerAmenities(stationId))
                .thenReturn(new StationAmenitiesResponse(stationId, List.of()));

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/amenities", stationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationId").value(stationId.toString()))
                .andExpect(jsonPath("$.data.amenities").isArray())
                .andExpect(jsonPath("$.data.amenities").isEmpty());

        verify(stationAmenitiesService).getOwnerAmenities(stationId);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void ownerCanReplaceTheWholeSetAndReceivesCanonicalShape() throws Exception {
        UUID stationId = UUID.randomUUID();
        when(stationAmenitiesService.replaceOwnerAmenities(eq(stationId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StationAmenitiesResponse(stationId, List.of("wifi", "food")));

        mockMvc.perform(put("/api/v1/owner/stations/{stationId}/amenities", stationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amenities\":[\"food\",\"wifi\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationId").value(stationId.toString()))
                .andExpect(jsonPath("$.data.amenities[0]").value("wifi"))
                .andExpect(jsonPath("$.data.amenities[1]").value("food"));

        verify(stationAmenitiesService).replaceOwnerAmenities(eq(stationId), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotReadOrReplaceAmenities() throws Exception {
        UUID stationId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/amenities", stationId))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/owner/stations/{stationId}/amenities", stationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amenities\":[]}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(stationAmenitiesService);
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCannotReadAmenities() throws Exception {
        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/amenities", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stationAmenitiesService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCannotReadAmenities() throws Exception {
        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/amenities", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stationAmenitiesService);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/amenities", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stationAmenitiesService);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void malformedOrWrongShapePayloadsAreRejectedBeforeMutation() throws Exception {
        UUID stationId = UUID.randomUUID();
        for (String body : List.of(
                "{",
                "null",
                "[]",
                "{}",
                "{\"amenities\":null}",
                "{\"amenities\":\"wifi\"}",
                "{\"amenities\":[null]}"
        )) {
            mockMvc.perform(put("/api/v1/owner/stations/{stationId}/amenities", stationId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(stationAmenitiesService);
    }
}
