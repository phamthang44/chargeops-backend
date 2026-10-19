package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.controller.OwnerBookingController;
import com.thang.chargeops.booking.dto.filter.BookingWindowMode;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.service.BookingServiceFailureService;
import com.thang.chargeops.booking.service.OwnerBookingService;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({OwnerBookingExportController.class, OwnerBookingController.class})
@Import({GlobalHandlerError.class, OwnerBookingExportControllerTest.SecurityConfig.class})
class OwnerBookingExportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnerBookingExportService exporter;
    @MockitoBean OwnerBookingService bookings;
    @MockitoBean BookingServiceFailureService serviceFailures;
    @MockitoBean RestAccessDeniedHandler accessDeniedHandler;
    @MockitoBean RestAuthenticationEntryPoint authenticationEntryPoint;

    @TestConfiguration @EnableMethodSecurity
    static class SecurityConfig {
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable).httpBasic(Customizer.withDefaults())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }

    @Test void ownerReceivesAttachmentAndExplicitFiltersWithoutCollidingWithDetailRoute() throws Exception {
        when(exporter.export(any(), anyString(), any(Locale.class)))
                .thenReturn(new OwnerBookingExportService.ExportFile(new byte[]{80, 75}, "chargeops-bookings.xlsx"));
        mvc.perform(get("/api/v1/owner/bookings/export").with(user("owner").roles("OWNER"))
                        .header("Accept-Language", "vi").param("sort", "startAt,asc").param("windowMode", "START_IN_WINDOW"))
                .andExpect(status().isOk()).andExpect(content().contentType(OwnerBookingWorkbookWriter.MIME))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"chargeops-bookings.xlsx\""))
                .andExpect(content().bytes(new byte[]{80, 75}));
        verify(exporter).export(new OwnerBookingFilter(null, null, null, null, null, BookingWindowMode.START_IN_WINDOW), "startAt,asc", Locale.forLanguageTag("vi"));
        verifyNoInteractions(bookings);
    }

    @ParameterizedTest @ValueSource(strings = {"DRIVER", "STAFF", "ADMIN"})
    void otherRolesCannotExport(String role) throws Exception {
        mvc.perform(get("/api/v1/owner/bookings/export").with(user("other").roles(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(exporter);
    }

    @Test void unauthenticatedCannotExport() throws Exception {
        mvc.perform(get("/api/v1/owner/bookings/export")).andExpect(status().isUnauthorized());
        verifyNoInteractions(exporter);
    }

    @Test void businessFailureRemainsJsonEvenWhenRequestAcceptsExcel() throws Exception {
        when(exporter.export(any(), anyString(), any(Locale.class)))
                .thenThrow(new AppException(OwnerBookingExportErrorCode.LIMIT_EXCEEDED));
        mvc.perform(get("/api/v1/owner/bookings/export").with(user("owner").roles("OWNER"))
                        .header("Accept", OwnerBookingWorkbookWriter.MIME + ", application/json"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("BKG_EXPORT_LIMIT_EXCEEDED"));
    }

    @Test void malformedFilterIsRejectedBeforeExport() throws Exception {
        mvc.perform(get("/api/v1/owner/bookings/export").with(user("owner").roles("OWNER"))
                .param("stationId", "invalid")).andExpect(status().isBadRequest());
        verifyNoInteractions(exporter);
    }
}
