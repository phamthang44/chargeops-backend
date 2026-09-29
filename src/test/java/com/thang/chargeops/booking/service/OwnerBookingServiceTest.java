package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.filter.OwnerActiveBookingFilter;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import com.thang.chargeops.booking.dto.response.OwnerBookingListItemResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.OwnerBookingPredicateFactory;
import com.thang.chargeops.booking.service.impl.OwnerBookingServiceImpl;
import com.thang.chargeops.booking.support.OwnerResourceScopeValidator;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerBookingServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00Z");

    @Mock private OwnerBookingReadRepository readRepository;
    @Mock private OwnerBookingPredicateFactory predicateFactory;
    @Mock private OwnerResourceScopeValidator scopeValidator;
    @Mock private CurrentProfileProvider currentProfileProvider;
    @Mock private BookingEffectiveStatePolicy effectiveStatePolicy;
    @Mock private OwnerBookingMapper mapper;
    @Mock private PaymentRepository paymentRepository;
    @Mock private BookingFinancialReadAssembler financialReadAssembler;
    @Mock private Clock applicationClock;
    @Mock private Specification<Booking> specification;
    @Mock private Booking booking;

    private OwnerBookingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OwnerBookingServiceImpl(
                readRepository, predicateFactory, scopeValidator,
                currentProfileProvider, effectiveStatePolicy, mapper,
                paymentRepository, financialReadAssembler, applicationClock
        );
        lenient().when(currentProfileProvider.requireProfileId()).thenReturn(OWNER_ID);
        lenient().when(applicationClock.instant()).thenReturn(NOW);
    }

    @Test
    void listUsesOneEvaluationInstantAndScopesBeforeQuery() {
        OwnerBookingFilter filter = new OwnerBookingFilter(STATION_ID, null, null, null, null);
        OwnerBookingListItemResponse item = mock(OwnerBookingListItemResponse.class);
        when(predicateFactory.forList(OWNER_ID, filter, NOW)).thenReturn(specification);
        when(readRepository.findAll(eq(specification), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(booking)));
        when(mapper.toListItem(booking, NOW)).thenReturn(item);

        var result = service.getOwnerBookings(filter, 1, 20);

        assertThat(result.getContent()).containsExactly(item);
        verify(applicationClock, times(1)).instant();
        verify(scopeValidator).validate(OWNER_ID, STATION_ID, null, null);
        verify(mapper).toListItem(booking, NOW);
    }

    @Test
    void activeForDoesNotReadFinancialData() {
        OwnerActiveBookingFilter filter = new OwnerActiveBookingFilter(STATION_ID, null, null);
        OperationalBookingResponse item = mock(OperationalBookingResponse.class);
        when(predicateFactory.forActive(OWNER_ID, filter, NOW)).thenReturn(specification);
        when(readRepository.findAll(eq(specification), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(booking)));
        when(mapper.toOperational(booking, NOW)).thenReturn(item);

        var result = service.getOwnerActiveBookingsFor(filter, 1, 20);

        assertThat(result.getContent()).containsExactly(item);
        verifyNoInteractions(paymentRepository, financialReadAssembler);
    }

    @Test
    void invalidWindowFailsBeforeScopeOrDatabaseAccess() {
        OwnerBookingFilter filter = new OwnerBookingFilter(
                null, null, NOW, NOW, null
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.getOwnerBookingSummary(filter)
        ).isInstanceOf(com.thang.chargeops.exception.AppException.class);

        verifyNoInteractions(scopeValidator, readRepository);
        verify(applicationClock, never()).instant();
    }
}
