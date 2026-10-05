package com.thang.chargeops.booking.scheduler;

import com.thang.chargeops.booking.projection.BookingLifecycleCandidateProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingNoShowService;
import com.thang.chargeops.common.exception.SystemConfigException;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingNoShowSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:45:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingNoShowService noShowService;

    private BookingNoShowScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BookingNoShowScheduler(
                bookingRepository,
                noShowService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50
        );
    }

    @Test
    void dispatchesOnlyNoShowCandidatesWithOneClockSnapshot() {
        BookingLifecycleCandidateProjection candidate = candidate();
        PageRequest page = PageRequest.of(0, 50);
        when(bookingRepository.findDueNoShowCandidates(NOW, page))
                .thenReturn(List.of(candidate));

        scheduler.markDueBookingsAsNoShow();

        verify(noShowService).markNoShowIfDue(
                candidate.getBookingId(),
                candidate.getConnectorId(),
                NOW
        );
        verify(bookingRepository, never())
                .findDueSessionCompletionCandidates(any(), any());
    }

    @Test
    void conflictDoesNotPreventRemainingNoShowCandidates() {
        BookingLifecycleCandidateProjection first = candidate();
        BookingLifecycleCandidateProjection second = candidate();
        when(bookingRepository.findDueNoShowCandidates(
                NOW,
                PageRequest.of(0, 50)
        )).thenReturn(List.of(first, second));
        when(noShowService.markNoShowIfDue(
                first.getBookingId(),
                first.getConnectorId(),
                NOW
        )).thenThrow(new OptimisticLockingFailureException("race"));

        scheduler.markDueBookingsAsNoShow();

        verify(noShowService).markNoShowIfDue(
                second.getBookingId(),
                second.getConnectorId(),
                NOW
        );
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new BookingNoShowScheduler(
                bookingRepository,
                noShowService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                0
        )).isInstanceOf(SystemConfigException.class);
    }

    private BookingLifecycleCandidateProjection candidate() {
        BookingLifecycleCandidateProjection candidate = mock(BookingLifecycleCandidateProjection.class);
        lenient().when(candidate.getBookingId()).thenReturn(UUID.randomUUID());
        lenient().when(candidate.getConnectorId()).thenReturn(UUID.randomUUID());
        return candidate;
    }
}
