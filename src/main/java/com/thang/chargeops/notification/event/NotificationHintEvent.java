package com.thang.chargeops.notification.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Scalar, in-process hint published inside the domain transaction that changed the inbox and
 * delivered {@code AFTER_COMMIT} by {@code NotificationHintPublisher} (BKG-071 plan #69 §2.2).
 *
 * <p>Contract v1 keys: {@code v, eventId, type, ref}. The record carries no title, body, amount
 * or any other business field — the hint only tells a client <em>that</em> something changed;
 * the client refetches REST for the data itself.
 *
 * <p>{@code eventId} is deterministic ({@code kind:recipientId:sourceKey}) so a reconnecting
 * client can dedupe replays. Both {@code eventId} and {@code ref} are capped to a fixed
 * character budget; anything longer keeps a stable {@code -<sha256 prefix>} tail so truncation
 * can never make two distinct events collide.
 */
public record NotificationHintEvent(NotificationHintKind kind, String recipientKeycloakId, String ref, String eventId) {

    /** Plan #69 §2.2: the whole hint stays under a 200-byte UTF-8 budget. */
    public static final int MAX_PAYLOAD_BYTES = 200;
    /** Dedupe token budget; keeps the payload under budget with the longest wire type. */
    public static final int EVENT_ID_MAX_LENGTH = 64;
    /** Advisory lookup key budget: {@code 18 + 64 + 10 + 22 + 9 + 72 + 2 == 197} worst case. */
    public static final int REF_MAX_LENGTH = 72;

    private static final int HASH_SUFFIX_LENGTH = 12;

    public NotificationHintEvent {
        Objects.requireNonNull(kind, "kind is required");
        if (recipientKeycloakId == null || recipientKeycloakId.isBlank()) {
            throw new IllegalArgumentException("recipientKeycloakId is required");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
    }

    /** Notice row inserted ({@code sourceEventKey} identifies the notice). */
    public static NotificationHintEvent created(String recipientKeycloakId, String sourceEventKey) {
        return of(NotificationHintKind.CREATED, recipientKeycloakId, sourceEventKey, sourceEventKey);
    }

    /** Notice transitioned to read ({@code noticeId} identifies the notice). */
    public static NotificationHintEvent read(String recipientKeycloakId, String noticeId) {
        return of(NotificationHintKind.READ, recipientKeycloakId, noticeId, noticeId);
    }

    /** Notice transitioned to dismissed ({@code noticeId} identifies the notice). */
    public static NotificationHintEvent dismissed(String recipientKeycloakId, String noticeId) {
        return of(NotificationHintKind.DISMISSED, recipientKeycloakId, noticeId, noticeId);
    }

    /**
     * Scope-wide read-all. Exactly one hint regardless of how many rows changed; the scope key
     * ({@code context|category|stationId|all}) is only part of the dedupe token — the payload
     * carries no {@code ref} because there is no single notice to point at.
     */
    public static NotificationHintEvent readAll(String recipientKeycloakId, String scopeKey) {
        return of(NotificationHintKind.READ_ALL, recipientKeycloakId, null, scopeKey);
    }

    private static NotificationHintEvent of(NotificationHintKind kind, String recipientKeycloakId,
                                            String refSource, String eventIdSource) {
        Objects.requireNonNull(eventIdSource, "eventIdSource is required");
        String ref = refSource == null ? null : shorten(refSource, REF_MAX_LENGTH);
        String eventId = shorten(
                kind.name() + ":" + recipientKeycloakId + ":" + eventIdSource, EVENT_ID_MAX_LENGTH);
        return new NotificationHintEvent(kind, recipientKeycloakId, ref, eventId);
    }

    /**
     * Caps {@code value} at {@code max} characters. A shortened value keeps a digest of the
     * full input so distinct inputs stay distinct after truncation (dedupe must never swallow
     * a second, genuinely different hint).
     */
    static String shorten(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int hashChars = HASH_SUFFIX_LENGTH;
        int prefixChars = max - hashChars - 1;
        return value.substring(0, prefixChars) + "-" + sha256Prefix(value, hashChars);
    }

    private static String sha256Prefix(String value, int hexChars) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, hexChars);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory on every JRE; fail loudly rather than emit a colliding token.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
