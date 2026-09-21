package com.thang.chargeops.booking.scheduler;

import com.thang.chargeops.booking.projection.BookingExpirationCandidateProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingExpirationService;
import com.thang.chargeops.common.exception.SystemConfigException;
import com.thang.chargeops.exception.errorcode.SystemConfigErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingExpirationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingExpirationService bookingExpirationService;

    private BookingExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BookingExpirationScheduler(
                bookingRepository,
                bookingExpirationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50
        );
    }

    private BookingExpirationCandidateProjection mockCandidate(UUID bookingId, UUID connectorId) {
        BookingExpirationCandidateProjection candidate = mock(BookingExpirationCandidateProjection.class);
        when(candidate.getBookingId()).thenReturn(bookingId);
        when(candidate.getConnectorId()).thenReturn(connectorId);
        return candidate;
    }

    @Test
    @DisplayName("Scheduler expires candidates using consistent clock timestamp")
    void expiresCandidatesUsingConsistentTimestamp() {
        UUID booking1 = UUID.randomUUID();
        UUID connector1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        UUID connector2 = UUID.randomUUID();

        BookingExpirationCandidateProjection c1 = mockCandidate(booking1, connector1);
        BookingExpirationCandidateProjection c2 = mockCandidate(booking2, connector2);

        when(bookingRepository.findOverduePendingCandidates(eq(NOW), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(c1, c2));
        when(bookingExpirationService.expireIfDue(booking1, connector1, NOW)).thenReturn(true);
        when(bookingExpirationService.expireIfDue(booking2, connector2, NOW)).thenReturn(true);

        scheduler.expireDueBookings();

        verify(bookingExpirationService).expireIfDue(booking1, connector1, NOW);
        verify(bookingExpirationService).expireIfDue(booking2, connector2, NOW);
    }

    @Test
    @DisplayName("Scheduler continues batch when one candidate encounters conflict/exception")
    void continuesBatchWhenOneCandidateThrowsConflict() {
        UUID booking1 = UUID.randomUUID();
        UUID connector1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        UUID connector2 = UUID.randomUUID();

        BookingExpirationCandidateProjection c1 = mockCandidate(booking1, connector1);
        BookingExpirationCandidateProjection c2 = mockCandidate(booking2, connector2);

        when(bookingRepository.findOverduePendingCandidates(eq(NOW), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(c1, c2));
        when(bookingExpirationService.expireIfDue(booking1, connector1, NOW))
                .thenThrow(new OptimisticLockingFailureException("Concurrent modification"));
        when(bookingExpirationService.expireIfDue(booking2, connector2, NOW)).thenReturn(true);

        scheduler.expireDueBookings();

        verify(bookingExpirationService).expireIfDue(booking1, connector1, NOW);
        verify(bookingExpirationService).expireIfDue(booking2, connector2, NOW);
    }

    @Test
    @DisplayName("Scheduler skips candidate with null bookingId or connectorId")
    void skipsCandidateWithNullIds() {
        BookingExpirationCandidateProjection invalidCandidate = mock(BookingExpirationCandidateProjection.class);
        when(invalidCandidate.getBookingId()).thenReturn(null);

        UUID validBooking = UUID.randomUUID();
        UUID validConnector = UUID.randomUUID();
        BookingExpirationCandidateProjection validCandidate = mockCandidate(validBooking, validConnector);

        when(bookingRepository.findOverduePendingCandidates(eq(NOW), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(invalidCandidate, validCandidate));
        when(bookingExpirationService.expireIfDue(validBooking, validConnector, NOW)).thenReturn(true);

        scheduler.expireDueBookings();

        verify(bookingExpirationService, never()).expireIfDue(eq(null), any(), any());
        verify(bookingExpirationService).expireIfDue(validBooking, validConnector, NOW);
    }

    @Test
    @DisplayName("Scheduler handles empty candidate list cleanly")
    void handlesEmptyCandidateListCleanly() {
        when(bookingRepository.findOverduePendingCandidates(eq(NOW), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of());

        scheduler.expireDueBookings();

        verify(bookingExpirationService, never()).expireIfDue(any(), any(), any());
    }

    @Test
    @DisplayName("Scheduler rejects a non-positive batch size with a configuration error")
    void rejectsInvalidBatchSizeWithConfigurationError() {
        assertThatThrownBy(() -> new BookingExpirationScheduler(
                bookingRepository,
                bookingExpirationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                0
        ))
                .isInstanceOf(SystemConfigException.class)
                .satisfies(error -> assertThat(((SystemConfigException) error).getErrorCode())
                        .isEqualTo(SystemConfigErrorCode.OUT_OF_RANGE));
    }

    @Test
    @DisplayName("Scheduler keeps processing after an unexpected candidate error")
    void continuesBatchWhenOneCandidateThrowsUnexpectedError() {
        UUID booking1 = UUID.randomUUID();
        UUID connector1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        UUID connector2 = UUID.randomUUID();

        BookingExpirationCandidateProjection c1 = mockCandidate(booking1, connector1);
        BookingExpirationCandidateProjection c2 = mockCandidate(booking2, connector2);

        when(bookingRepository.findOverduePendingCandidates(eq(NOW), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(c1, c2));
        when(bookingExpirationService.expireIfDue(booking1, connector1, NOW))
                .thenThrow(new IllegalStateException("test failure"));
        when(bookingExpirationService.expireIfDue(booking2, connector2, NOW)).thenReturn(true);

        scheduler.expireDueBookings();

        verify(bookingExpirationService).expireIfDue(booking2, connector2, NOW);
    }
}
