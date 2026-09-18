package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.BookingStatsResponse;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.DriverBookingListItemResponse;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.model.DriverBookingHistoryResult;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void bookingHistoryBindsSearchStatusAndReturnsFacetCounts() throws Exception {
        @SuppressWarnings("unchecked")
        Page<DriverBookingListItemResponse> pageResult = mock(Page.class);
        when(pageResult.getContent()).thenReturn(List.of());
        when(pageResult.getNumber()).thenReturn(1);
        when(pageResult.getSize()).thenReturn(15);
        when(pageResult.getTotalElements()).thenReturn(3L);
        when(pageResult.getTotalPages()).thenReturn(1);

        DriverBookingHistoryResult result = new DriverBookingHistoryResult(
                pageResult,
                Map.of("all", 4L, "completed", 1L, "cancelled", 3L)
        );
        when(bookingService.getMyBookingHistory(
                any(DriverBookingHistoryFilter.class),
                eq(2),
                eq(15)
        )).thenReturn(result);

        mockMvc.perform(get("/api/v1/bookings/history")
                        .param("query", "alpha")
                        .param("status", "CANCELLED")
                        .param("page", "2")
                        .param("size", "15"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.counts.all").value(4))
                .andExpect(jsonPath("$.meta.counts.completed").value(1))
                .andExpect(jsonPath("$.meta.counts.cancelled").value(3));

        var filterCaptor = org.mockito.ArgumentCaptor.forClass(
                DriverBookingHistoryFilter.class
        );
        verify(bookingService).getMyBookingHistory(
                filterCaptor.capture(),
                eq(2),
                eq(15)
        );
        assertThat(filterCaptor.getValue().query()).isEqualTo("alpha");
        assertThat(filterCaptor.getValue().status())
                .isEqualTo(DriverBookingHistoryFilter.HistoryStatus.CANCELLED);
    }

    @Test
    void getMyBookingStatsReturnsDriverLifetimeStats() throws Exception {
        BookingStatsResponse stats = BookingStatsResponse.of(
                BigDecimal.valueOf(250_000),
                3L,
                5L,
                3L,
                2L,
                4.5
        );
        when(bookingService.getMyBookingStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/bookings/stats"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.totalSpending").value(250000))
                .andExpect(jsonPath("$.data.totalChargingSessions").value(3))
                .andExpect(jsonPath("$.data.totalBookings").value(5))
                .andExpect(jsonPath("$.data.totalCompletedBookings").value(3))
                .andExpect(jsonPath("$.data.totalCancelledBookings").value(2))
                .andExpect(jsonPath("$.data.totalHours").value(4.5))
                .andExpect(jsonPath("$.data.spent").value(250000))
                .andExpect(jsonPath("$.data.sessions").value(3))
                .andExpect(jsonPath("$.data.hours").value(4.5));

        verify(bookingService).getMyBookingStats();
    }

    @Test
    void bookingDetailReturnsDomainForbiddenWhenDriverDoesNotOwnBooking()
            throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.getMyBooking(bookingId))
                .thenThrow(new AppException(
                        BookingErrorCode.BOOKING_NOT_ACCESS
                ));

        mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BKG_NOT_ACCESS"))
                .andExpect(jsonPath("$.error.messageKey")
                        .value("error.booking.notAccess"));
    }

    @Test
    void createBooking_success_returns201WithPendingBookingAndPayment() throws Exception {
        UUID requestKey = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-16T03:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T04:00:00Z");
        Instant expiresAt = Instant.parse("2026-09-16T02:10:00Z");
        String pricingVersion = "a".repeat(64);

        CreateBookingResponse response = CreateBookingResponse.builder()
                .bookingId(bookingId)
                .bookingCode("BK-20260916-0001")
                .status(BookingStatus.PENDING)
                .version(0L)
                .connectorId(connectorId)
                .startAt(startAt)
                .endAt(endAt)
                .durationMin(60)
                .totalAmount(126000L)
                .currency("VND")
                .paymentHoldExpiresAt(expiresAt)
                .payment(new CreateBookingResponse.PaymentSummary(paymentId, PaymentStatus.PENDING, PaymentMethod.SIMULATOR))
                .checkout(new CreateBookingResponse.CheckoutSummary(CheckoutStatus.NOT_CREATED))
                .build();

        when(bookingService.createNewBooking(eq(requestKey), any(CreateBookingRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", requestKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-16T03:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "booking-v4.9",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(connectorId, pricingVersion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.data.bookingCode").value("BK-20260916-0001"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalAmount").value(126000))
                .andExpect(jsonPath("$.data.payment.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.data.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.data.checkout.status").value("NOT_CREATED"));
    }

    @Test
    void createBooking_missingIdempotencyKey_returns400BadRequest() throws Exception {
        UUID connectorId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-16T03:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "booking-v4.9",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(connectorId, "a".repeat(64))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBooking_priceChanged_returns409ConflictWithLatestPricePreview() throws Exception {
        UUID requestKey = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<String, Object> latestPreview = Map.of(
                "totalAmount", 156000,
                "pricingVersion", "b".repeat(64)
        );
        when(bookingService.createNewBooking(eq(requestKey), any(CreateBookingRequest.class)))
                .thenThrow(AppException.withDetails(
                        BookingErrorCode.PRICE_CHANGED,
                        Map.of("latestPricePreview", latestPreview)
                ));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", requestKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-16T03:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "booking-v4.9",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(connectorId, "a".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BKG_PRICE_CHANGED"))
                .andExpect(jsonPath("$.error.details.latestPricePreview.totalAmount").value(156000));
    }

    @Test
    void createBooking_slotUnavailable_returns409Conflict() throws Exception {
        UUID requestKey = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        when(bookingService.createNewBooking(eq(requestKey), any(CreateBookingRequest.class)))
                .thenThrow(new AppException(BookingErrorCode.SLOT_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", requestKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-16T03:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "booking-v4.9",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(connectorId, "a".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BKG_SLOT_UNAVAILABLE"));
    }

    @Test
    void createBooking_pendingLimitExceeded_returns409Conflict() throws Exception {
        UUID requestKey = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        when(bookingService.createNewBooking(eq(requestKey), any(CreateBookingRequest.class)))
                .thenThrow(new AppException(BookingErrorCode.PENDING_LIMIT_EXCEEDED));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", requestKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-16T03:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "booking-v4.9",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(connectorId, "a".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BKG_PENDING_LIMIT_EXCEEDED"));
    }
}
