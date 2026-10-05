package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.repository.StationOperationalBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.StationOperationalBookingPredicateFactory;
import com.thang.chargeops.booking.service.impl.StationOperationalBookingServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.station.access.service.StationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StationOperationalBookingServiceTest {

    private static final UUID STATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOOKING_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Mock private StationAccessService stationAccessService;
    @Mock private StationOperationalBookingReadRepository readRepository;
    @Mock private StationOperationalBookingPredicateFactory predicateFactory;
    @Mock private OwnerBookingMapper mapper;
    @Mock private Clock applicationClock;
    @Mock private Specification<Booking> specification;
    @Mock private Booking booking;

    private StationOperationalBookingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationOperationalBookingServiceImpl(
                stationAccessService,
                readRepository,
                predicateFactory,
                mapper,
                applicationClock
        );
        lenient().when(applicationClock.instant()).thenReturn(NOW);
    }

    @Test
    void listRequiresOwnerOrActiveStaffAndUsesOneEvaluationInstant() {
        StationOperationalBookingFilter filter = new StationOperationalBookingFilter(CONNECTOR_ID, null, null);
        OperationalBookingResponse responseItem = OperationalBookingResponse.builder()
                .bookingId(BOOKING_ID)
                .bookingCode("BKG-001")
                .status(BookingStatus.CONFIRMED)
                .stationId(STATION_ID)
                .connectorId(CONNECTOR_ID)
                .connectorCode("C1")
                .driverDisplayName("Driver John")
                .startAt(NOW)
                .endAt(NOW.plusSeconds(3600))
                .checkInDeadline(NOW.plusSeconds(900))
                .build();

        when(predicateFactory.forStation(STATION_ID, filter)).thenReturn(specification);
        when(readRepository.findAll(eq(specification), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(booking)));
        when(mapper.toOperational(booking, NOW)).thenReturn(responseItem);

        var result = service.getOperationalBookings(STATION_ID, filter, 1, 20);

        assertThat(result.getContent()).containsExactly(responseItem);
        verify(stationAccessService).requireOwnerOrActiveStaff(STATION_ID);
        verify(applicationClock, times(1)).instant();
        verify(mapper).toOperational(booking, NOW);
    }

    @Test
    void listRejectsInvalidPaging() {
        StationOperationalBookingFilter filter = new StationOperationalBookingFilter(null, null, null);

        assertThatThrownBy(() -> service.getOperationalBookings(STATION_ID, filter, 0, 20))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));

        assertThatThrownBy(() -> service.getOperationalBookings(STATION_ID, filter, 1, 0))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));

        assertThatThrownBy(() -> service.getOperationalBookings(STATION_ID, filter, 1, 101))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));

        verifyNoInteractions(stationAccessService, readRepository);
    }

    @Test
    void listRejectsInvalidTimeWindow() {
        StationOperationalBookingFilter filter = new StationOperationalBookingFilter(
                null,
                NOW,
                NOW.minusSeconds(60)
        );

        assertThatThrownBy(() -> service.getOperationalBookings(STATION_ID, filter, 1, 20))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));

        verifyNoInteractions(stationAccessService, readRepository);
    }

    @Test
    void detailReturnsOperationalBookingWhenBelongsToStation() {
        OperationalBookingResponse responseItem = OperationalBookingResponse.builder()
                .bookingId(BOOKING_ID)
                .bookingCode("BKG-001")
                .status(BookingStatus.CONFIRMED)
                .stationId(STATION_ID)
                .connectorId(CONNECTOR_ID)
                .connectorCode("C1")
                .driverDisplayName("Driver John")
                .startAt(NOW)
                .endAt(NOW.plusSeconds(3600))
                .checkInDeadline(NOW.plusSeconds(900))
                .build();

        when(readRepository.findOperationalDetail(BOOKING_ID, STATION_ID)).thenReturn(Optional.of(booking));
        when(mapper.toOperational(booking, NOW)).thenReturn(responseItem);

        var result = service.getOperationalBooking(STATION_ID, BOOKING_ID);

        assertThat(result).isSameAs(responseItem);
        verify(stationAccessService).requireOwnerOrActiveStaff(STATION_ID);
        verify(mapper).toOperational(booking, NOW);
    }

    @Test
    void detailThrowsBookingNotAccessWhenBookingExistsUnderAnotherStation() {
        when(readRepository.findOperationalDetail(BOOKING_ID, STATION_ID)).thenReturn(Optional.empty());
        when(readRepository.existsById(BOOKING_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.getOperationalBooking(STATION_ID, BOOKING_ID))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(BookingErrorCode.BOOKING_NOT_ACCESS));

        verify(stationAccessService).requireOwnerOrActiveStaff(STATION_ID);
        verify(mapper, never()).toOperational(any(), any());
    }

    @Test
    void detailThrowsResourceNotFoundWhenBookingDoesNotExistGlobally() {
        when(readRepository.findOperationalDetail(BOOKING_ID, STATION_ID)).thenReturn(Optional.empty());
        when(readRepository.existsById(BOOKING_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getOperationalBooking(STATION_ID, BOOKING_ID))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));

        verify(stationAccessService).requireOwnerOrActiveStaff(STATION_ID);
        verify(mapper, never()).toOperational(any(), any());
    }

    @Test
    void operationalBookingResponseEnforcesZeroFinancialLeakage() {
        List<String> componentNames = Arrays.stream(OperationalBookingResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .toList();

        List<String> forbiddenKeywords = List.of(
                "amount", "price", "payment", "refund", "currency", "fee", "cost", "action", "checkout"
        );

        for (String forbidden : forbiddenKeywords) {
            assertThat(componentNames)
                    .as("OperationalBookingResponse must not contain financial field '%s'", forbidden)
                    .noneMatch(name -> name.contains(forbidden));
        }
    }
}
