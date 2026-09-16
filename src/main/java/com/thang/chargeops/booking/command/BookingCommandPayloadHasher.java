package com.thang.chargeops.booking.command;

import com.thang.chargeops.booking.service.model.CanonicalPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Hashes a caller-built canonical payload string. */
public final class BookingCommandPayloadHasher {

    private BookingCommandPayloadHasher() {
    }

    public static String sha256(String rawPayload) {
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String sha256(CanonicalPayload canonicalPayload) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload must not be null");
        return sha256(String.join("\n",
                "create-booking-v1",
                field("connectorId", canonicalPayload.connectorId()),
                field("startAt", canonicalPayload.startAt()),
                field("durationMin", canonicalPayload.durationMin()),
                field("acceptedTotalAmount", canonicalPayload.acceptedTotalAmount()),
                field("acceptedPricingVersion", canonicalPayload.acceptedPricingVersion()),
                field("acceptedPolicyVersion", canonicalPayload.acceptedPolicyVersion()),
                field("paymentMethod", canonicalPayload.paymentMethod())
        ));
    }

    private static String field(String name, Object value) {
        String text = Objects.requireNonNull(value, name + " must not be null").toString();
        return name + ":" + text.length() + ":" + text;
    }
}
