package com.thang.chargeops.booking.history;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/** Records history inside a transaction owned by the calling application service. */
@Service
@RequiredArgsConstructor
public class BookingStatusHistoryRecorder {

    private final BookingStatusHistoryRepository historyRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreation(
            BookingCommand command,
            BookingStatusActorType actorType,
            Instant occurredAt
    ) {
        Objects.requireNonNull(command, BookingHistoryInvariantMessages.COMMAND_REQUIRED);
        Booking booking = command.getBooking();
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new IllegalStateException(BookingHistoryInvariantMessages.ONLY_PENDING_CREATION);
        }
        historyRepository.save(BookingStatusHistory.userTransition(
                command,
                actorType,
                new BookingStatusChange(null, BookingStatus.PENDING, null, occurredAt)
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserTransition(
            BookingCommand command,
            BookingStatusActorType actorType,
            BookingStatusReason reason,
            Instant occurredAt,
            Consumer<Booking> transition
    ) {
        Objects.requireNonNull(command, BookingHistoryInvariantMessages.COMMAND_REQUIRED);
        Booking booking = command.getBooking();
        BookingStatus fromStatus = requireCurrentStatus(booking);
        Objects.requireNonNull(
                transition,
                BookingHistoryInvariantMessages.TRANSITION_REQUIRED
        ).accept(booking);
        historyRepository.save(BookingStatusHistory.userTransition(
                command,
                actorType,
                new BookingStatusChange(fromStatus, booking.getStatus(), reason, occurredAt)
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSystemTransition(
            Booking booking,
            BookingStatusReason reason,
            Instant occurredAt,
            Consumer<Booking> transition
    ) {
        BookingStatus fromStatus = requireCurrentStatus(booking);
        Objects.requireNonNull(
                transition,
                BookingHistoryInvariantMessages.TRANSITION_REQUIRED
        ).accept(booking);
        historyRepository.save(BookingStatusHistory.systemTransition(
                booking,
                new BookingStatusChange(fromStatus, booking.getStatus(), reason, occurredAt)
        ));
    }

    private static BookingStatus requireCurrentStatus(Booking booking) {
        Objects.requireNonNull(booking, BookingHistoryInvariantMessages.BOOKING_REQUIRED);
        return Objects.requireNonNull(
                booking.getStatus(),
                BookingHistoryInvariantMessages.BOOKING_STATUS_REQUIRED
        );
    }
}
