package com.thang.chargeops.payment.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class SepayWebhookVerifier {

    @Value("${sepay.webhook-secret}")
    private String secretKey;

    public boolean verify(
            byte[] rawBody,
            String timestamp,
            String receivedSignature
    ) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );

            mac.init(secretKeySpec);

            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');

            byte[] hash = mac.doFinal(rawBody);

            String expectedSignature =
                    "sha256=" + HexFormat.of().formatHex(hash);

            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8)
            );

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot verify SePay webhook", e);
        }
    }
}