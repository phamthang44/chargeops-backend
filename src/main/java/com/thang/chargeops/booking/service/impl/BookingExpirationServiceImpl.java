package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingExpirationService;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingExpirationServiceImpl implements BookingExpirationService {

    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfDue(UUID bookingId, UUID connectorId, Instant now) {
        if (bookingId == null || connectorId == null || now == null) {
            return false;
        }

        // 1. Lock Connector
        Optional<Connector> connectorOpt = connectorRepository.findByIdWithLock(connectorId);
        if (connectorOpt.isEmpty()) {
            log.warn("Connector {} not found during expiration check for booking {}", connectorId, bookingId);
            return false;
        }

        // 2. Lock Booking
        Optional<Booking> bookingOpt = bookingRepository.findByIdWithLock(bookingId);
        if (bookingOpt.isEmpty()) {
            log.warn("Booking {} not found during expiration check", bookingId);
            return false;
        }
        Booking booking = bookingOpt.get();

        // 3. Lock Payment (if exists)
        Optional<Payment> paymentOpt = paymentRepository.findByBookingIdWithLock(bookingId);

        // 4. Re-check conditions under lock
        if (booking.getStatus() != BookingStatus.PENDING) {
            log.debug("Booking {} is no longer PENDING (status={}), skipping expiration", bookingId, booking.getStatus());
            return false;
        }

        if (booking.getExpiresAt() == null || booking.getExpiresAt().isAfter(now)) {
            log.debug("Booking {} is not overdue (expiresAt={}), skipping expiration", bookingId, booking.getExpiresAt());
            return false;
        }

        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            if (payment.getStatus() == PaymentStatus.PAID) {
                log.info("Booking {} has PAID payment, skipping expiration", bookingId);
                return false;
            }
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.markFailed();
            }
        }

        // 5. Expire booking and record history
        bookingStatusHistoryRecorder.recordSystemTransition(
                booking,
                BookingStatusReason.HOLD_EXPIRED,
                now,
                Booking::expire
        );

        log.info("Expired overdue pending booking {} on connector {}", bookingId, connectorId);
        return true;
    }
}
