package com.thang.chargeops.booking.scheduler;

import com.thang.chargeops.booking.projection.BookingLifecycleCandidateProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingAutomaticCompletionService;
import com.thang.chargeops.common.exception.SystemConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingSessionCompletionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-27T11:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingAutomaticCompletionService completionService;

    private BookingSessionCompletionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BookingSessionCompletionScheduler(
                bookingRepository,
                completionService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                25
        );
    }

    @Test
    void dispatchesOnlyCompletionCandidatesWithIndependentBatchSize() {
        BookingLifecycleCandidateProjection candidate = candidate();
        PageRequest page = PageRequest.of(0, 25);
        when(bookingRepository.findDueSessionCompletionCandidates(NOW, page))
                .thenReturn(List.of(candidate));

        scheduler.completeDueSessions();

        verify(completionService).completeIfDue(
                candidate.getBookingId(),
                candidate.getConnectorId(),
                NOW
        );
        verify(bookingRepository, never()).findDueNoShowCandidates(any(), any());
    }

    @Test
    void dataConflictDoesNotPreventRemainingCompletionCandidates() {
        BookingLifecycleCandidateProjection first = candidate();
        BookingLifecycleCandidateProjection second = candidate();
        when(bookingRepository.findDueSessionCompletionCandidates(
                NOW,
                PageRequest.of(0, 25)
        )).thenReturn(List.of(first, second));
        when(completionService.completeIfDue(
                first.getBookingId(),
                first.getConnectorId(),
                NOW
        )).thenThrow(new DataIntegrityViolationException("race"));

        scheduler.completeDueSessions();

        verify(completionService).completeIfDue(
                second.getBookingId(),
                second.getConnectorId(),
                NOW
        );
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new BookingSessionCompletionScheduler(
                bookingRepository,
                completionService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                -1
        )).isInstanceOf(SystemConfigException.class);
    }

    private BookingLifecycleCandidateProjection candidate() {
        BookingLifecycleCandidateProjection candidate = mock(BookingLifecycleCandidateProjection.class);
        lenient().when(candidate.getBookingId()).thenReturn(UUID.randomUUID());
        lenient().when(candidate.getConnectorId()).thenReturn(UUID.randomUUID());
        return candidate;
    }
}
