package com.thang.chargeops.payment.utils;

import com.thang.chargeops.payment.config.SepayTestModeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "app.sepay", name = "enabled", havingValue = "true")
public class SepayWebhookVerifier {

    private final SepayTestModeProperties properties;
    private final Clock applicationClock;

    public SepayWebhookVerifier(SepayTestModeProperties properties, Clock applicationClock) {
        properties.validateForUse();
        this.properties = properties;
        this.applicationClock = applicationClock;
    }

    public boolean verify(byte[] rawBody, String timestamp, String receivedSignature) {
        if (rawBody == null || timestamp == null || receivedSignature == null) return false;
        try {
            long signedAt = Long.parseLong(timestamp);
            long now = applicationClock.instant().getEpochSecond();
            long tolerance = properties.getWebhookToleranceSeconds();
            if (signedAt < now - tolerance || signedAt > now + tolerance) return false;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            String expectedSignature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException exception) {
            return false;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot verify SePay webhook", exception);
        }
    }
}
