package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.service.OwnerBookingService;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerBookingController.class)
@Import({GlobalHandlerError.class, OwnerBookingControllerSecurityTest.SecurityConfig.class})
class OwnerBookingControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OwnerBookingService ownerBookingService;
    @MockitoBean private RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean private RestAccessDeniedHandler accessDeniedHandler;

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
    void controllerRequiresOwnerRoleAtClassLevel() {
        PreAuthorize guard = OwnerBookingController.class.getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("hasRole('OWNER')");
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void driverCannotReadOwnerBookings() throws Exception {
        mockMvc.perform(get("/api/v1/owner/bookings"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ownerBookingService);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void ownerListIsOneBasedAndNoStore() throws Exception {
        when(ownerBookingService.getOwnerBookings(any(OwnerBookingFilter.class), eq(1), eq(20)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/owner/bookings"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void invalidPagingAndMissingActiveStationAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/owner/bookings").param("page", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/owner/bookings").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/owner/bookings/active-for"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ownerBookingService);
    }
}
