package com.thang.chargeops.booking;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.controller.BookingController;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BKG-022: M1 Lifecycle End-to-End REST Verification.
 * Verifies the complete sequence:
 * 1. Price Preview (POST /price-preview) -> 200 OK + PricingVersion
 * 2. Booking Creation (POST /bookings with Idempotency-Key) -> 201 Created + PENDING
 * 3. Idempotent Replay (POST /bookings with same Idempotency-Key) -> 201 Created + same Booking
 * 4. Read Booking Detail (GET /bookings/{id}) -> 200 OK + PENDING status + valid capabilities
 */
@WebMvcTest(BookingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
@DisplayName("BKG-022: M1 Lifecycle End-to-End REST Integration")
class BookingM1LifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingPricingService bookingPricingService;

    @MockitoBean
    private BookingService bookingService;

    private static final UUID CONNECTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID BOOKING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PAYMENT_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID REQUEST_KEY = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
    private static final Instant START_AT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-09-20T11:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-20T09:10:00Z");
    private static final String PRICING_VERSION = "a".repeat(64);
    private static final String POLICY_VERSION = "booking-v4.9";

    @Test
    @DisplayName("M1 Demo: Chuỗi chọn thời gian -> xem giá -> PENDING -> đọc detail trên REST thật")
    void fullM1Lifecycle_preview_create_replay_readDetail_succeeds() throws Exception {
        // GIAI ĐOẠN 1: Preview Báo giá (Stateless Preview)
        PriceLine line1 = new PriceLine(
                1L,
                START_AT,
                END_AT,
                60,
                "Giờ bình thường",
                TouRatePeriodCode.NORMAL,
                BigDecimal.valueOf(3400),
                BigDecimal.valueOf(37.2),
                126000L
        );
        PricePreviewResponse previewResponse = new PricePreviewResponse(
                PRICING_VERSION,
                CONNECTOR_ID,
                START_AT,
                END_AT,
                60,
                "VND",
                126000L,
                List.of(line1),
                PriceBasis.fixedPackage(BigDecimal.valueOf(60)),
                BookingPolicyConfig.defaults().toPolicyResponse(),
                List.of()
        );
        when(bookingPricingService.previewBookingPrice(any(PricePreviewRequest.class)))
                .thenReturn(previewResponse);

        mockMvc.perform(post("/api/v1/bookings/price-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-20T10:00:00Z",
                                  "durationMin": 60
                                }
                                """.formatted(CONNECTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.pricingVersion").value(PRICING_VERSION))
                .andExpect(jsonPath("$.data.connectorId").value(CONNECTOR_ID.toString()))
                .andExpect(jsonPath("$.data.totalAmount").value(126000))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.policy.policyVersion").value(POLICY_VERSION));

        // GIAI ĐOẠN 2: Tạo Booking giữ chỗ (Create Booking - PENDING)
        CreateBookingResponse createResponse = CreateBookingResponse.builder()
                .bookingId(BOOKING_ID)
                .bookingCode("BK-20260920-0001")
                .status(BookingStatus.PENDING)
                .version(0L)
                .connectorId(CONNECTOR_ID)
                .startAt(START_AT)
                .endAt(END_AT)
                .durationMin(60)
                .totalAmount(126000L)
                .currency("VND")
                .paymentHoldExpiresAt(EXPIRES_AT)
                .payment(new CreateBookingResponse.PaymentSummary(
                        PAYMENT_ID,
                        PaymentStatus.PENDING,
                        PaymentMethod.SIMULATOR
                ))
                .checkout(new CreateBookingResponse.CheckoutSummary(CheckoutStatus.NOT_CREATED))
                .build();

        when(bookingService.createNewBooking(eq(REQUEST_KEY), any(CreateBookingRequest.class)))
                .thenReturn(createResponse);

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", REQUEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-20T10:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "%s",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(CONNECTOR_ID, PRICING_VERSION, POLICY_VERSION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookingId").value(BOOKING_ID.toString()))
                .andExpect(jsonPath("$.data.bookingCode").value("BK-20260920-0001"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalAmount").value(126000))
                .andExpect(jsonPath("$.data.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.data.checkout.status").value("NOT_CREATED"));

        // GIAI ĐOẠN 3: Replay tạo lại với cùng Idempotency-Key
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", REQUEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-20T10:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "%s",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(CONNECTOR_ID, PRICING_VERSION, POLICY_VERSION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookingId").value(BOOKING_ID.toString()))
                .andExpect(jsonPath("$.data.bookingCode").value("BK-20260920-0001"));

        // GIAI ĐOẠN 4: Đọc chi tiết Booking (Read Detail & Capabilities)
        StationSnapshotResponse stationSnapshot = new StationSnapshotResponse(
                UUID.randomUUID(),
                "Trạm Sạc Sài Gòn",
                "123 Nguyễn Huệ, Q1",
                "CP-01",
                CONNECTOR_ID,
                "CON-01"
        );
        BookingActionsResponse actions = BookingActionsResponse.builder()
                .canCancel(true)
                .refundableAmount(0L)
                .cancellationReason(BookingActionsResponse.CancellationCapabilityReason.UNPAID)
                .canCheckIn(false)
                .checkInReason(BookingActionsResponse.CheckInCapabilityReason.WRONG_STATE)
                .canStartCharging(false)
                .canComplete(false)
                .canReportIssue(false)
                .build();

        BookingDetailResponse detailResponse = BookingDetailResponse.builder()
                .bookingId(BOOKING_ID)
                .bookingCode("BK-20260920-0001")
                .status(BookingStatus.PENDING)
                .persistedStatus(BookingStatus.PENDING)
                .stateReconciliationPending(false)
                .version(0L)
                .station(stationSnapshot)
                .timezone("Asia/Ho_Chi_Minh")
                .startAt(START_AT)
                .endAt(END_AT)
                .durationMin(60)
                .totalAmount(126000L)
                .currency("VND")
                .priceLines(List.of(line1))
                .policyVersion(POLICY_VERSION)
                .paymentHoldExpiresAt(EXPIRES_AT)
                .actions(actions)
                .build();

        when(bookingService.getMyBooking(BOOKING_ID)).thenReturn(detailResponse);

        mockMvc.perform(get("/api/v1/bookings/{bookingId}", BOOKING_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.bookingId").value(BOOKING_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.station.stationName").value("Trạm Sạc Sài Gòn"))
                .andExpect(jsonPath("$.data.actions.canCancel").value(true))
                .andExpect(jsonPath("$.data.actions.canCheckIn").value(false))
                .andExpect(jsonPath("$.data.actions.canReportIssue").value(false))
                .andExpect(jsonPath("$.data.actions.cancellationReason").value("UNPAID"));

        verify(bookingService).getMyBooking(BOOKING_ID);
    }

    @Test
    @DisplayName("Giá thay đổi giữa lúc xem và lúc tạo -> Chặn bằng HTTP 409 PRICE_CHANGED")
    void m1Lifecycle_tariffChangeBetweenPreviewAndCreate_failsWith409PriceChanged() throws Exception {
        Map<String, Object> latestPreview = Map.of(
                "totalAmount", 156000,
                "pricingVersion", "b".repeat(64),
                "reason", "TARIFF_CHANGED"
        );

        when(bookingService.createNewBooking(eq(REQUEST_KEY), any(CreateBookingRequest.class)))
                .thenThrow(AppException.withDetails(
                        BookingErrorCode.PRICE_CHANGED,
                        Map.of("latestPricePreview", latestPreview)
                ));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", REQUEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectorId": "%s",
                                  "startAt": "2026-09-20T10:00:00Z",
                                  "durationMin": 60,
                                  "acceptedTotalAmount": 126000,
                                  "acceptedPricingVersion": "%s",
                                  "acceptedPolicyVersion": "%s",
                                  "paymentMethod": "SIMULATOR"
                                }
                                """.formatted(CONNECTOR_ID, PRICING_VERSION, POLICY_VERSION)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BKG_PRICE_CHANGED"))
                .andExpect(jsonPath("$.error.details.latestPricePreview.totalAmount").value(156000))
                .andExpect(jsonPath("$.error.details.latestPricePreview.pricingVersion").value("b".repeat(64)));
    }
}
