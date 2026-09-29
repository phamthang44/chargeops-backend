package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.repository.StationOperationalBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.StationOperationalBookingPredicateFactory;
import com.thang.chargeops.booking.service.StationOperationalBookingService;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.station.access.service.StationAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationOperationalBookingServiceImpl implements StationOperationalBookingService {

    private final StationAccessService stationAccessService;
    private final StationOperationalBookingReadRepository readRepository;
    private final StationOperationalBookingPredicateFactory predicateFactory;
    private final OwnerBookingMapper mapper;
    private final Clock applicationClock;

    @Override
    public Page<OperationalBookingResponse> getOperationalBookings(
            UUID stationId, StationOperationalBookingFilter filter, int page, int size) {
        validatePaging(page, size);
        validateWindow(filter);
        stationAccessService.requireOwnerOrActiveStaff(stationId);
        Instant evaluatedAt = applicationClock.instant();
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
        return readRepository.findAll(predicateFactory.forStation(stationId, filter), pageable)
                .map(booking -> mapper.toOperational(booking, evaluatedAt));
    }

    @Override
    public OperationalBookingResponse getOperationalBooking(UUID stationId, UUID bookingId) {
        stationAccessService.requireOwnerOrActiveStaff(stationId);
        Instant evaluatedAt = applicationClock.instant();
        Booking booking = readRepository.findOperationalDetail(bookingId, stationId).orElseGet(() -> {
            if (readRepository.existsById(bookingId)) {
                throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
            }
            throw new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, bookingId);
        });
        return mapper.toOperational(booking, evaluatedAt);
    }

    private void validatePaging(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private void validateWindow(StationOperationalBookingFilter filter) {
        if (filter != null && filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST, "from must be before to");
        }
    }
}
