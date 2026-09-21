package com.thang.chargeops.booking.scheduler;

import com.thang.chargeops.booking.projection.BookingExpirationCandidateProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingExpirationService;
import com.thang.chargeops.common.exception.SystemConfigException;
import com.thang.chargeops.exception.errorcode.SystemConfigErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class BookingExpirationScheduler {

    private final BookingRepository bookingRepository;
    private final BookingExpirationService bookingExpirationService;
    private final Clock clock;
    private final int batchSize;

    public BookingExpirationScheduler(
            BookingRepository bookingRepository,
            BookingExpirationService bookingExpirationService,
            Clock clock,
            @Value("${app.scheduling.booking-expiration.batch-size:100}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new SystemConfigException(SystemConfigErrorCode.OUT_OF_RANGE);
        }
        this.bookingRepository = bookingRepository;
        this.bookingExpirationService = bookingExpirationService;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.scheduling.booking-expiration.fixed-delay-ms:30000}",
            initialDelayString = "${app.scheduling.booking-expiration.initial-delay-ms:30000}"
    )
    public void expireDueBookings() {
        Instant now = clock.instant();
        List<BookingExpirationCandidateProjection> candidates = bookingRepository.findOverduePendingCandidates(
                now,
                PageRequest.of(0, batchSize)
        );

        int expiredCount = 0;
        int conflictCount = 0;
        int errorCount = 0;

        for (BookingExpirationCandidateProjection candidate : candidates) {
            if (candidate.getBookingId() == null || candidate.getConnectorId() == null) {
                continue;
            }
            try {
                if (bookingExpirationService.expireIfDue(candidate.getBookingId(), candidate.getConnectorId(), now)) {
                    expiredCount++;
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                conflictCount++;
                log.warn(
                        "Skipped automatic expiration for booking {} because of a concurrent data conflict",
                        candidate.getBookingId()
                );
            } catch (RuntimeException exception) {
                errorCount++;
                log.error(
                        "Automatic expiration failed for booking {}; continuing the remaining batch",
                        candidate.getBookingId(),
                        exception
                );
            }
        }

        if (!candidates.isEmpty()) {
            log.info(
                    "Booking expiration run completed: candidates={}, expired={}, conflicts={}, errors={}",
                    candidates.size(),
                    expiredCount,
                    conflictCount,
                    errorCount
            );
        }
    }
}
