package com.thang.chargeops.payment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.payment.dto.request.SepayWebhookRequest;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.payment.utils.SepayWebhookVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "webhooks/sepay")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.sepay", name = "enabled", havingValue = "true")
public class SepayWebhookController {

    private static final DateTimeFormatter SEPAY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SepayWebhookVerifier webhookVerifier;
    private final PaymentConfirmationService paymentConfirmationService;
    private final ObjectMapper objectMapper;
    private final Clock applicationClock;

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> handleWebhook(
            @RequestHeader("X-SePay-Signature") String signature,
            @RequestHeader("X-SePay-Timestamp") String timestamp,
            @RequestBody byte[] rawBody
    ) {
        if (!webhookVerifier.verify(rawBody, timestamp, signature)) {
            log.warn("Rejected SePay webhook with invalid signature or stale timestamp");
            return response(HttpStatus.UNAUTHORIZED, false);
        }

        try {
            String body = new String(rawBody, StandardCharsets.UTF_8);
            SepayWebhookRequest request = objectMapper.readValue(body, SepayWebhookRequest.class);
            validateRequest(request);
            if (!"in".equalsIgnoreCase(request.transferType())) {
                log.info("Ignored non-incoming SePay transfer: id={}, type={}",
                        request.id(), request.transferType());
                return response(HttpStatus.OK, true);
            }

            paymentConfirmationService.processReceipt(toReceipt(request, body));
            return response(HttpStatus.OK, true);
        } catch (IllegalArgumentException exception) {
            log.warn("Rejected malformed SePay webhook: {}", exception.getMessage());
            return response(HttpStatus.BAD_REQUEST, false);
        } catch (RuntimeException exception) {
            log.error("SePay webhook processing failed; returning retryable status", exception);
            return response(HttpStatus.SERVICE_UNAVAILABLE, false);
        }
    }

    private void validateRequest(SepayWebhookRequest request) {
        if (request == null || request.id() == null || isBlank(request.transferType())
                || isBlank(request.accountNumber()) || isBlank(request.code())
                || isBlank(request.subAccount()) || request.transferAmount() == null
                || request.transferAmount() <= 0 || isBlank(request.transactionDate())) {
            throw new IllegalArgumentException("Missing required SePay receipt evidence");
        }
    }

    private NormalizedReceipt toReceipt(SepayWebhookRequest request, String rawPayload) {
        try {
            Instant providerPaidAt = LocalDateTime.parse(request.transactionDate(), SEPAY_DATE_FORMAT)
                    .atZone(SEPAY_ZONE).toInstant();
            return new NormalizedReceipt(
                    "SEPAY", request.accountNumber(), String.valueOf(request.id()),
                    BigDecimal.valueOf(request.transferAmount()), "VND", providerPaidAt,
                    applicationClock.instant(), request.subAccount(), request.code(),
                    request.content(), rawPayload);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid SePay transactionDate", exception);
        }
    }

    private ResponseEntity<Map<String, Boolean>> response(HttpStatus status, boolean success) {
        return ResponseEntity.status(status).body(Map.of("success", success));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
