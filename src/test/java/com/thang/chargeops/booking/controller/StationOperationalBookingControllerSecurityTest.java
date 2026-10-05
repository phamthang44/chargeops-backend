package com.thang.chargeops.booking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import com.thang.chargeops.booking.service.StationOperationalBookingService;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
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

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StationOperationalBookingController.class)
@Import({GlobalHandlerError.class, StationOperationalBookingControllerSecurityTest.SecurityConfig.class})
class StationOperationalBookingControllerSecurityTest {

    private static final UUID STATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOOKING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONNECTOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @MockitoBean private StationOperationalBookingService stationOperationalBookingService;
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
    void controllerRequiresAuthenticatedAtClassLevel() {
        PreAuthorize guard = StationOperationalBookingController.class.getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("isAuthenticated()");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/stations/{stationId}/bookings", STATION_ID))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stationOperationalBookingService);
    }

    @Test
    @WithMockUser(username = "active-staff")
    void authenticatedUserGetsBookingsWithNoStoreHeader() throws Exception {
        when(stationOperationalBookingService.getOperationalBookings(
                eq(STATION_ID), any(StationOperationalBookingFilter.class), eq(1), eq(20)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/stations/{stationId}/bookings", STATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @WithMockUser(username = "revoked-staff")
    void accessDeniedFromServiceTranslatesToForbidden() throws Exception {
        when(stationOperationalBookingService.getOperationalBookings(
                eq(STATION_ID), any(StationOperationalBookingFilter.class), eq(1), eq(20)))
                .thenThrow(new AppException(StationErrorCode.STATION_ACCESS_DENIED));

        mockMvc.perform(get("/api/v1/stations/{stationId}/bookings", STATION_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "staff")
    void stationNotFoundFromServiceTranslatesToNotFound() throws Exception {
        when(stationOperationalBookingService.getOperationalBookings(
                eq(STATION_ID), any(StationOperationalBookingFilter.class), eq(1), eq(20)))
                .thenThrow(new AppException(StationErrorCode.STATION_NOT_FOUND, STATION_ID));

        mockMvc.perform(get("/api/v1/stations/{stationId}/bookings", STATION_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "staff")
    void bookingOutsideStationScopeTranslatesToForbidden() throws Exception {
        when(stationOperationalBookingService.getOperationalBooking(STATION_ID, BOOKING_ID))
                .thenThrow(new AppException(BookingErrorCode.BOOKING_NOT_ACCESS));

        mockMvc.perform(get("/api/v1/stations/{stationId}/bookings/{bookingId}", STATION_ID, BOOKING_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "staff")
    void zeroFinancialLeakageJsonSnapshotTest() throws Exception {
        OperationalBookingResponse response = OperationalBookingResponse.builder()
                .bookingId(BOOKING_ID)
                .bookingCode("BKG-260929-ABCD")
                .status(BookingStatus.CONFIRMED)
                .stationId(STATION_ID)
                .connectorId(CONNECTOR_ID)
                .connectorCode("CP01-C1")
                .driverDisplayName("Nguyen Van A")
                .startAt(NOW)
                .endAt(NOW.plusSeconds(3600))
                .checkInDeadline(NOW.plusSeconds(900))
                .build();

        when(stationOperationalBookingService.getOperationalBooking(STATION_ID, BOOKING_ID))
                .thenReturn(response);

        var result = mockMvc.perform(get("/api/v1/stations/{stationId}/bookings/{bookingId}", STATION_ID, BOOKING_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.get("data");

        assertThat(data).isNotNull();
        assertThat(data.has("bookingId")).isTrue();
        assertThat(data.has("bookingCode")).isTrue();
        assertThat(data.has("status")).isTrue();
        assertThat(data.has("stationId")).isTrue();
        assertThat(data.has("connectorId")).isTrue();
        assertThat(data.has("connectorCode")).isTrue();
        assertThat(data.has("driverDisplayName")).isTrue();

        // Strict verification: zero financial and administrative fields
        List<String> forbiddenSubstrings = List.of(
                "amount", "price", "payment", "refund", "currency", "cost", "fee", "rate", "bill", "action", "checkout"
        );

        Iterator<String> fieldNames = data.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next().toLowerCase();
            for (String forbidden : forbiddenSubstrings) {
                assertThat(field)
                        .as("Operational payload MUST NOT contain financial property '%s'", forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }
}
