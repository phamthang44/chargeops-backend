package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.event.BookingLifecycleEvent;
import com.thang.chargeops.booking.event.BookingLifecycleEventType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingNoShowService;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingNoShowServiceImpl implements BookingNoShowService {

    private static final String NO_SHOW_REASON = "NO_SHOW";

    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markNoShowIfDue(
            UUID bookingId,
            UUID connectorId,
            Instant decisionAt
    ) {
        if (bookingId == null || connectorId == null || decisionAt == null) {
            return false;
        }

        Optional<Connector> connector = connectorRepository.findByIdWithLock(connectorId);
        if (connector.isEmpty()) {
            log.warn("Connector {} not found during no-show check for booking {}", connectorId, bookingId);
            return false;
        }

        Optional<Booking> bookingOptional = bookingRepository.findByIdWithLock(bookingId);
        if (bookingOptional.isEmpty()) {
            log.warn("Booking {} not found during no-show check", bookingId);
            return false;
        }
        Booking booking = bookingOptional.get();

        if (booking.getStatus() != BookingStatus.CONFIRMED
                || booking.getCheckInDeadline() == null
                || decisionAt.isBefore(booking.getCheckInDeadline())) {
            return false;
        }

        Optional<Payment> paymentOptional = paymentRepository.findByBookingIdWithLock(bookingId);
        if (paymentOptional.isEmpty() || paymentOptional.get().getStatus() != PaymentStatus.PAID) {
            log.warn("Skipping no-show for confirmed booking {} because its payment is not PAID", bookingId);
            return false;
        }

        bookingStatusHistoryRecorder.recordSystemTransition(
                booking,
                BookingStatusReason.NO_SHOW,
                decisionAt,
                current -> current.cancel(NO_SHOW_REASON, decisionAt)
        );
        bookingRepository.flush();
        eventPublisher.publishEvent(new BookingLifecycleEvent(
                bookingId,
                BookingLifecycleEventType.NO_SHOW,
                decisionAt
        ));
        return true;
    }
}
