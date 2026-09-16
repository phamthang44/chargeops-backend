package com.thang.chargeops.booking.history;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only audit record for one Booking lifecycle transition. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking_status_history", indexes = {
        @Index(name = "idx_booking_status_history_booking_time", columnList = "booking_id, occurred_at, id")
}, uniqueConstraints = {
        @UniqueConstraint(
                name = "ux_booking_status_history_booking_command",
                columnNames = {"booking_id", "command_id"}
        )
})
public class BookingStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 30)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private BookingStatus toStatus;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private BookingStatusActorType actorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_profile_id", updatable = false)
    private UserProfile actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "command_id", updatable = false)
    private BookingCommand command;

    public static BookingStatusHistory userTransition(
            BookingCommand command,
            BookingStatusActorType actorType,
            BookingStatusChange change
    ) {
        if (actorType == null || actorType == BookingStatusActorType.SYSTEM) {
            throw new IllegalArgumentException(
                    BookingHistoryInvariantMessages.USER_TRANSITION_REQUIRES_HUMAN_ACTOR
            );
        }
        Objects.requireNonNull(
                command,
                BookingHistoryInvariantMessages.COMMAND_REQUIRED
        );
        Objects.requireNonNull(change, BookingHistoryInvariantMessages.TRANSITION_REQUIRED);
        return create(
                command.getBooking(),
                change,
                actorType,
                command.getActor(),
                command
        );
    }

    public static BookingStatusHistory systemTransition(
            Booking booking,
            BookingStatusChange change
    ) {
        return create(
                booking,
                change,
                BookingStatusActorType.SYSTEM,
                null,
                null
        );
    }

    private static BookingStatusHistory create(
            Booking booking,
            BookingStatusChange change,
            BookingStatusActorType actorType,
            UserProfile actor,
            BookingCommand command
    ) {
        Objects.requireNonNull(booking, BookingHistoryInvariantMessages.BOOKING_REQUIRED);
        Objects.requireNonNull(change, BookingHistoryInvariantMessages.TRANSITION_REQUIRED);
        requireValidTransition(change.fromStatus(), change.toStatus());

        BookingStatusHistory history = new BookingStatusHistory();
        history.booking = booking;
        history.fromStatus = change.fromStatus();
        history.toStatus = change.toStatus();
        history.reason = change.reason() == null ? null : change.reason().name();
        history.actorType = actorType;
        history.actor = actor;
        history.occurredAt = change.occurredAt();
        history.command = command;
        return history;
    }

    private static void requireValidTransition(BookingStatus fromStatus, BookingStatus toStatus) {
        boolean valid = fromStatus == null
                ? toStatus == BookingStatus.PENDING
                : switch (fromStatus) {
                    case PENDING -> toStatus == BookingStatus.CONFIRMED
                            || toStatus == BookingStatus.CANCELLED
                            || toStatus == BookingStatus.EXPIRED;
                    case CONFIRMED -> toStatus == BookingStatus.CHECKED_IN
                            || toStatus == BookingStatus.CANCELLED;
                    case CHECKED_IN -> toStatus == BookingStatus.CHARGING
                            || toStatus == BookingStatus.COMPLETED;
                    case CHARGING -> toStatus == BookingStatus.COMPLETED;
                    case COMPLETED, EXPIRED, CANCELLED -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException(
                    BookingHistoryInvariantMessages.invalidTransition(fromStatus, toStatus)
            );
        }
    }
}
