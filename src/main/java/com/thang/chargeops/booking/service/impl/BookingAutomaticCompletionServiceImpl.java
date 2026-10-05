package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.event.BookingLifecycleEvent;
import com.thang.chargeops.booking.event.BookingLifecycleEventType;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingAutomaticCompletionService;
import com.thang.chargeops.booking.service.BookingSessionCompletionCoordinator;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingAutomaticCompletionServiceImpl implements BookingAutomaticCompletionService {

    private static final EnumSet<BookingStatus> COMPLETABLE_STATUSES =
            EnumSet.of(BookingStatus.CHECKED_IN, BookingStatus.CHARGING);

    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final BookingSessionCompletionCoordinator completionCoordinator;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeIfDue(
            UUID bookingId,
            UUID connectorId,
            Instant decisionAt
    ) {
        if (bookingId == null || connectorId == null || decisionAt == null) {
            return false;
        }

        Optional<Connector> connectorOptional = connectorRepository.findByIdWithLock(connectorId);
        if (connectorOptional.isEmpty()) {
            log.warn("Connector {} not found during automatic completion for booking {}", connectorId, bookingId);
            return false;
        }

        Optional<Booking> bookingOptional = bookingRepository.findByIdWithLock(bookingId);
        if (bookingOptional.isEmpty()) {
            log.warn("Booking {} not found during automatic completion", bookingId);
            return false;
        }
        Booking booking = bookingOptional.get();

        if (!COMPLETABLE_STATUSES.contains(booking.getStatus())
                || booking.getEndAt() == null
                || decisionAt.isBefore(booking.getEndAt())) {
            return false;
        }

        completionCoordinator.completeBySystem(
                booking,
                connectorOptional.get(),
                decisionAt
        );
        eventPublisher.publishEvent(new BookingLifecycleEvent(
                bookingId,
                BookingLifecycleEventType.SESSION_COMPLETED,
                decisionAt
        ));
        return true;
    }
}
