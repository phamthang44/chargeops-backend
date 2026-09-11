package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.exception.GlobalHandlerError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingPricingService bookingPricingService;

    @MockitoBean
    private com.thang.chargeops.booking.service.BookingService bookingService;

    @Test
    void previewBookingPriceReturnsNoStorePricePreview() throws Exception {
        UUID connectorId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-10T10:00:00Z");
        PricePreviewResponse response = new PricePreviewResponse(
                "a".repeat(64),
                connectorId,
                startAt,
                startAt.plusSeconds(3600),
                60,
                "VND",
                126000L,
                List.of(),
                PriceBasis.fixedPackage(BigDecimal.valueOf(60)),
                BookingPolicyConfig.defaults().toPolicyResponse(),
                List.of()
        );
        when(bookingPricingService.previewBookingPrice(any(PricePreviewRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings/price-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-10T10:00:00Z",
                                  "durationMin": 60
                                }
                                """.formatted(connectorId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.pricingVersion").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.connectorId").value(connectorId.toString()))
                .andExpect(jsonPath("$.data.totalAmount").value(126000))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.priceLines", hasSize(0)))
                .andExpect(jsonPath("$.data.pricingBasis.energyFactor").value(0.62))
                .andExpect(jsonPath("$.data.policy.policyVersion").value("booking-v4.9"));
    }
}
