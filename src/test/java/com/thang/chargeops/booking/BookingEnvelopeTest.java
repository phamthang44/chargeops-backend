package com.thang.chargeops.booking;

import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BKG-005 acceptance tests:
 * 1. Verifies registry, message keys, and i18n lookup for all BookingErrorCode (BKG_*) and CommandErrorCode (CMD_*).
 * 2. Verifies standard ApiResult envelope (success with numeric epoch serverTime, 1-based pagination, error envelope).
 * 3. Verifies PRICE_CHANGED and CANCELLATION_CHANGED exceptions with structured details fixtures serialize to HTTP 409 Conflict without invoking business services.
 */
@WebMvcTest(BookingEnvelopeTest.EnvelopeProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class BookingEnvelopeTest {

    @Autowired
    private MockMvc mockMvc;

    private final GlobalHandlerError globalHandler = new GlobalHandlerError();

    // ─────────────────────────────────────────────────────────────────────────
    // Probe Controller strictly confined within test scope
    // ─────────────────────────────────────────────────────────────────────────

    @RestController
    @RequestMapping("/test/bkg-005")
    static class EnvelopeProbeController {

        @GetMapping("/success")
        public ResponseEntity<ApiResult<Map<String, Object>>> probeSuccess() {
            return ResponseEntity.ok(ApiResult.success(Map.of(
                    "status", "ACTIVE",
                    "connectorId", "00000000-0000-4000-8000-000000000003"
            )));
        }

        @GetMapping("/price-changed-fixture")
        public ResponseEntity<ApiResult<?>> probePriceChanged() {
            throw AppException.withDetails(
                    BookingErrorCode.PRICE_CHANGED,
                    Map.of("latestPricePreview", createLatestPricePreviewFixture())
            );
        }

        @GetMapping("/cancellation-changed-fixture")
        public ResponseEntity<ApiResult<?>> probeCancellationChanged() {
            throw AppException.withDetails(
                    BookingErrorCode.CANCELLATION_CHANGED,
                    Map.of("currentBooking", createCurrentBookingFixture())
            );
        }

        @GetMapping("/command-key-reused")
        public ResponseEntity<ApiResult<?>> probeCommandKeyReused() {
            throw new AppException(CommandErrorCode.KEY_REUSED);
        }

        @GetMapping("/command-in-progress")
        public ResponseEntity<ApiResult<?>> probeCommandInProgress() {
            throw new AppException(CommandErrorCode.IN_PROGRESS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture Builders
    // ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> createLatestPricePreviewFixture() {
        return Map.of(
                "connectorId", "00000000-0000-4000-8000-000000000003",
                "startAt", "2026-09-07T03:00:00Z",
                "endAt", "2026-09-07T04:00:00Z",
                "durationMin", 60,
                "currency", "VND",
                "totalAmount", 156000L,
                "priceLines", List.of(Map.of(
                        "sequence", 1,
                        "rateVndPerKwh", 4200,
                        "estimatedEnergyKwh", 37.2,
                        "amount", 156000L
                )),
                "pricingBasis", Map.of(
                        "kind", "ESTIMATED_ENERGY_FIXED_PACKAGE",
                        "energyFactor", 0.62,
                        "powerKw", 60
                ),
                "policy", Map.of(
                        "policyVersion", "booking-v4.9",
                        "advanceMinMinutes", 60,
                        "paymentHoldMin", 10,
                        "cancellationGraceMin", 10
                ),
                "pricingVersion", "573ccd0635a01a98b9722fbeef446ade5db3323e8fa6bc66fba58e04d9ae01ed"
        );
    }

    private static Map<String, Object> createCurrentBookingFixture() {
        return Map.of(
                "bookingId", UUID.fromString("11111111-1111-4000-8000-111111111111"),
                "status", "CONFIRMED",
                "cancellationDeadline", "2026-09-07T02:50:00Z",
                "expectedRefundAmount", 0L,
                "refundPercentage", 0,
                "version", 2
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Suites
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1. Error Code Registry and Message Templates (BKG_* and CMD_*)")
    class ErrorCodeRegistryTests {

        @Test
        @DisplayName("All BookingErrorCode values have BKG_ prefix, HTTP status, and registered message templates")
        void verifyBookingErrorCodes() {
            for (BookingErrorCode code : BookingErrorCode.values()) {
                assertThat(code.getCode())
                        .as("Code %s must start with BKG_", code)
                        .startsWith("BKG_");

                assertThat(code.getHttpStatus())
                        .as("HTTP status for %s must not be null", code)
                        .isNotNull();

                assertThat(code.getMessageKey())
                        .as("Message key for %s must not be blank", code)
                        .isNotBlank()
                        .startsWith("error.booking.");

                assertThat(ErrorMessage.findByKey(code.getMessageKey()))
                        .as("Template for key %s must be registered in ErrorMessage registry", code.getMessageKey())
                        .isPresent();
            }
        }

        @Test
        @DisplayName("All CommandErrorCode values have CMD_ prefix, HTTP status 409, and registered message templates")
        void verifyCommandErrorCodes() {
            for (CommandErrorCode code : CommandErrorCode.values()) {
                assertThat(code.getCode())
                        .as("Code %s must start with CMD_", code)
                        .startsWith("CMD_");

                assertThat(code.getHttpStatus())
                        .as("HTTP status for %s must be 409 Conflict", code)
                        .isEqualTo(HttpStatus.CONFLICT);

                assertThat(code.getMessageKey())
                        .as("Message key for %s must not be blank", code)
                        .isNotBlank()
                        .startsWith("error.command.");

                assertThat(ErrorMessage.findByKey(code.getMessageKey()))
                        .as("Template for key %s must be registered in ErrorMessage registry", code.getMessageKey())
                        .isPresent();
            }
        }
    }

    @Nested
    @DisplayName("2. Envelope Structure and Pagination Semantics")
    class EnvelopeStructureTests {

        @Test
        @DisplayName("Success envelope contains payload and auto-filled metadata with numeric epoch serverTime")
        void successEnvelopeHasNumericServerTimeAndTraceId() {
            long before = System.currentTimeMillis();
            ApiResult<String> result = ApiResult.success("booking-confirmed");
            long after = System.currentTimeMillis();

            assertThat(result.getData()).isEqualTo("booking-confirmed");
            assertThat(result.getError()).isNull();

            ApiResult.Meta meta = result.getMeta();
            assertThat(meta).isNotNull();
            assertThat(meta.getServerTime())
                    .isGreaterThanOrEqualTo(before)
                    .isLessThanOrEqualTo(after);
            assertThat(meta.getApiVersion()).isEqualTo("1.0.0");
            assertThat(meta.getTraceId()).isNotBlank();
        }

        @Test
        @DisplayName("Offset pagination envelope maps 0-based Spring page to 1-based API response page")
        void paginationEnvelopeStartsFromPageOne() {
            Page<String> springPage = new PageImpl<>(
                    List.of("item-1", "item-2"),
                    PageRequest.of(0, 10),
                    2
            );

            ApiResult<List<String>> result = ApiResult.successPage(springPage);

            assertThat(result.getData()).containsExactly("item-1", "item-2");
            assertThat(result.getError()).isNull();

            ApiResult.Meta meta = result.getMeta();
            assertThat(meta.getPage()).isEqualTo(1); // One-based API contract
            assertThat(meta.getSize()).isEqualTo(10);
            assertThat(meta.getTotalElements()).isEqualTo(2L);
            assertThat(meta.getTotalPages()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("3. Conflict HTTP 409 Serialization with Structured Details")
    class ConflictDetailsSerializationTests {

        @Test
        @DisplayName("Translator handles PRICE_CHANGED and serializes structured latestPricePreview fixture into HTTP 409")
        void translatorHandlesPriceChangedWithStructuredDetails() {
            Map<String, Object> latestPreview = createLatestPricePreviewFixture();
            AppException ex = AppException.withDetails(
                    BookingErrorCode.PRICE_CHANGED,
                    Map.of("latestPricePreview", latestPreview)
            );

            ResponseEntity<ApiResult<?>> response = globalHandler.handleAppException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiResult<?> body = response.getBody();
            assertThat(body.getData()).isNull();
            assertThat(body.getMeta()).isNull();

            ApiResult.ErrorDetail error = body.getError();
            assertThat(error).isNotNull();
            assertThat(error.getCode()).isEqualTo("BKG_PRICE_CHANGED");
            assertThat(error.getMessageKey()).isEqualTo("error.booking.priceChanged");
            assertThat(error.getMessage()).isEqualTo("The booking price changed; review the latest preview");
            assertThat(error.getTraceId()).isNotBlank();
            assertThat(error.getDetails()).isInstanceOf(Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> detailsMap = (Map<String, Object>) error.getDetails();
            assertThat(detailsMap).containsKey("latestPricePreview");
        }

        @Test
        @DisplayName("Translator handles CANCELLATION_CHANGED and serializes structured currentBooking fixture into HTTP 409")
        void translatorHandlesCancellationChangedWithStructuredDetails() {
            Map<String, Object> currentBooking = createCurrentBookingFixture();
            AppException ex = AppException.withDetails(
                    BookingErrorCode.CANCELLATION_CHANGED,
                    Map.of("currentBooking", currentBooking)
            );

            ResponseEntity<ApiResult<?>> response = globalHandler.handleAppException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiResult<?> body = response.getBody();
            assertThat(body.getData()).isNull();
            assertThat(body.getMeta()).isNull();

            ApiResult.ErrorDetail error = body.getError();
            assertThat(error).isNotNull();
            assertThat(error.getCode()).isEqualTo("BKG_CANCELLATION_CHANGED");
            assertThat(error.getMessageKey()).isEqualTo("error.booking.cancellationChanged");
            assertThat(error.getMessage()).isEqualTo("The cancellation terms changed; review the booking again");
            assertThat(error.getDetails()).isInstanceOf(Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> detailsMap = (Map<String, Object>) error.getDetails();
            assertThat(detailsMap).containsKey("currentBooking");
        }

        @Test
        @DisplayName("MockMvc request confirms HTTP 200 success envelope shape over the wire")
        void mockMvcConfirmsSuccessEnvelopeOverWire() throws Exception {
            mockMvc.perform(get("/test/bkg-005/success"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.connectorId").value("00000000-0000-4000-8000-000000000003"))
                    .andExpect(jsonPath("$.meta.serverTime").isNumber())
                    .andExpect(jsonPath("$.meta.apiVersion").value("1.0.0"))
                    .andExpect(jsonPath("$.meta.traceId").isString())
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("MockMvc request confirms HTTP 409 BKG_PRICE_CHANGED with latestPricePreview details over the wire")
        void mockMvcConfirmsPriceChanged409OverWire() throws Exception {
            mockMvc.perform(get("/test/bkg-005/price-changed-fixture"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.meta").doesNotExist())
                    .andExpect(jsonPath("$.error.code").value("BKG_PRICE_CHANGED"))
                    .andExpect(jsonPath("$.error.messageKey").value("error.booking.priceChanged"))
                    .andExpect(jsonPath("$.error.traceId").isString())
                    .andExpect(jsonPath("$.error.details.latestPricePreview.totalAmount").value(156000))
                    .andExpect(jsonPath("$.error.details.latestPricePreview.currency").value("VND"))
                    .andExpect(jsonPath("$.error.details.latestPricePreview.durationMin").value(60))
                    .andExpect(jsonPath("$.error.details.latestPricePreview.pricingVersion")
                            .value("573ccd0635a01a98b9722fbeef446ade5db3323e8fa6bc66fba58e04d9ae01ed"))
                    .andExpect(jsonPath("$.error.details.latestPricePreview.pricingBasis.energyFactor").value(0.62))
                    .andExpect(jsonPath("$.error.details.latestPricePreview.policy.policyVersion").value("booking-v4.9"))
                    .andExpect(jsonPath("$.error.details.latestPricePreview.priceLines", hasSize(1)));
        }

        @Test
        @DisplayName("MockMvc request confirms HTTP 409 BKG_CANCELLATION_CHANGED with currentBooking details over the wire")
        void mockMvcConfirmsCancellationChanged409OverWire() throws Exception {
            mockMvc.perform(get("/test/bkg-005/cancellation-changed-fixture"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.meta").doesNotExist())
                    .andExpect(jsonPath("$.error.code").value("BKG_CANCELLATION_CHANGED"))
                    .andExpect(jsonPath("$.error.messageKey").value("error.booking.cancellationChanged"))
                    .andExpect(jsonPath("$.error.traceId").isString())
                    .andExpect(jsonPath("$.error.details.currentBooking.bookingId")
                            .value("11111111-1111-4000-8000-111111111111"))
                    .andExpect(jsonPath("$.error.details.currentBooking.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.error.details.currentBooking.expectedRefundAmount").value(0))
                    .andExpect(jsonPath("$.error.details.currentBooking.version").value(2));
        }

        @Test
        @DisplayName("MockMvc request confirms HTTP 409 CMD_KEY_REUSED and CMD_IN_PROGRESS")
        void mockMvcConfirmsCommandConflictCodes() throws Exception {
            mockMvc.perform(get("/test/bkg-005/command-key-reused"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CMD_KEY_REUSED"))
                    .andExpect(jsonPath("$.error.messageKey").value("error.command.keyReused"));

            mockMvc.perform(get("/test/bkg-005/command-in-progress"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CMD_IN_PROGRESS"))
                    .andExpect(jsonPath("$.error.messageKey").value("error.command.inProgress"));
        }
    }
}
