package com.thang.chargeops.booking.scheduler;

import com.thang.chargeops.booking.projection.BookingLifecycleCandidateProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingAutomaticCompletionService;
import com.thang.chargeops.common.exception.SystemConfigException;
import com.thang.chargeops.exception.errorcode.SystemConfigErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** BKG-045 bounded poller. Every candidate is rechecked in its own transaction. */
@Component
@Slf4j
public class BookingSessionCompletionScheduler {

    private final BookingRepository bookingRepository;
    private final BookingAutomaticCompletionService automaticCompletionService;
    private final Clock clock;
    private final int batchSize;

    public BookingSessionCompletionScheduler(
            BookingRepository bookingRepository,
            BookingAutomaticCompletionService automaticCompletionService,
            Clock clock,
            @Value("${app.scheduling.booking-session-completion.batch-size:50}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new SystemConfigException(SystemConfigErrorCode.OUT_OF_RANGE);
        }
        this.bookingRepository = bookingRepository;
        this.automaticCompletionService = automaticCompletionService;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.scheduling.booking-session-completion.fixed-delay-ms:30000}",
            initialDelayString = "${app.scheduling.booking-session-completion.initial-delay-ms:45000}"
    )
    public void completeDueSessions() {
        Instant decisionAt = clock.instant();
        List<BookingLifecycleCandidateProjection> candidates =
                bookingRepository.findDueSessionCompletionCandidates(
                        decisionAt,
                        PageRequest.of(0, batchSize)
                );

        int changed = 0;
        int conflicts = 0;
        int errors = 0;
        int invalid = 0;
        for (BookingLifecycleCandidateProjection candidate : candidates) {
            if (candidate.getBookingId() == null || candidate.getConnectorId() == null) {
                invalid++;
                continue;
            }
            try {
                if (automaticCompletionService.completeIfDue(
                        candidate.getBookingId(),
                        candidate.getConnectorId(),
                        decisionAt
                )) {
                    changed++;
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                conflicts++;
                log.warn(
                        "Skipped automatic session completion for booking {} because of a concurrent data conflict",
                        candidate.getBookingId()
                );
            } catch (RuntimeException exception) {
                errors++;
                log.error(
                        "Automatic session completion failed for booking {}; continuing the remaining batch",
                        candidate.getBookingId(),
                        exception
                );
            }
        }

        if (!candidates.isEmpty()) {
            log.info(
                    "Booking session completion run completed: candidates={}, changed={}, conflicts={}, errors={}, invalid={}",
                    candidates.size(),
                    changed,
                    conflicts,
                    errors,
                    invalid
            );
        }
    }
}
