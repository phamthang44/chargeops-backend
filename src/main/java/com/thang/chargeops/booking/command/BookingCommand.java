package com.thang.chargeops.booking.command;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A durable receipt for one successfully committed Booking command. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking_commands", indexes = {
        @Index(name = "idx_booking_commands_booking", columnList = "booking_id")
}, uniqueConstraints = {
        @UniqueConstraint(
                name = "ux_booking_commands_actor_operation_key",
                columnNames = {"actor_profile_id", "operation", "request_key"}
        )
})
public class BookingCommand {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_profile_id", nullable = false, updatable = false)
    private UserProfile actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 50)
    private BookingCommandOperation operation;

    @Column(name = "request_key", nullable = false, updatable = false)
    private UUID requestKey;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static BookingCommand successful(
            UserProfile actor,
            BookingCommandOperation operation,
            UUID requestKey,
            String payloadHash,
            Booking booking,
            Instant createdAt
    ) {
        BookingCommand command = new BookingCommand();
        command.actor = Objects.requireNonNull(actor, "actor must not be null");
        command.operation = Objects.requireNonNull(operation, "operation must not be null");
        command.requestKey = Objects.requireNonNull(requestKey, "requestKey must not be null");
        command.payloadHash = normalizeHash(payloadHash);
        command.booking = Objects.requireNonNull(booking, "booking must not be null");
        command.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        return command;
    }

    public boolean matchesPayload(String candidateHash) {
        return payloadHash.equals(normalizeHash(candidateHash));
    }

    private static String normalizeHash(String value) {
        Objects.requireNonNull(value, "payloadHash must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException("payloadHash must be a lowercase SHA-256 hex value");
        }
        return normalized;
    }
}
