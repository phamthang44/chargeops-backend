package com.thang.chargeops.payment.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.payment.utils.SepayWebhookVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "webhooks/sepay")
@RequiredArgsConstructor
@Slf4j
public class SepayWebhookController {

    private final SepayWebhookVerifier webhookVerifier;

    @PostMapping
    public ResponseEntity<?> handleWebhook(
            @RequestHeader("X-SePay-Signature") String signature,
            @RequestHeader("X-SePay-Timestamp") String timestamp,
            @RequestBody byte[] rawBody
    ) {

        boolean valid = webhookVerifier.verify(
                rawBody,
                timestamp,
                signature
        );

        if (!valid) {
            log.warn("Invalid SePay webhook signature");

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false));
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);

        log.info("Valid SePay webhook: {}", body);

        return ResponseEntity.ok(
                Map.of("success", true)
        );
    }

}
