package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.dto.filter.OwnerActiveBookingFilter;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.OwnerBookingPredicateFactory;
import com.thang.chargeops.booking.service.BookingFinancialReadAssembler;
import com.thang.chargeops.booking.service.OwnerBookingService;
import com.thang.chargeops.booking.support.OwnerResourceScopeValidator;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
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
public class OwnerBookingServiceImpl implements OwnerBookingService {
    private final OwnerBookingReadRepository readRepository;
    private final OwnerBookingPredicateFactory predicateFactory;
    private final OwnerResourceScopeValidator scopeValidator;
    private final CurrentProfileProvider currentProfileProvider;
    private final BookingEffectiveStatePolicy effectiveStatePolicy;
    private final OwnerBookingMapper mapper;
    private final PaymentRepository paymentRepository;
    private final BookingFinancialReadAssembler financialReadAssembler;
    private final Clock applicationClock;

    @Override
    public Page<OwnerBookingListItemResponse> getOwnerBookings(OwnerBookingFilter filter, int page, int size) {
        validatePaging(page, size); validateWindow(filter);
        UUID ownerId = currentProfileProvider.requireProfileId();
        Instant evaluatedAt = applicationClock.instant();
        scopeValidator.validate(ownerId, filter.stationId(), null, filter.connectorId());
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
        return readRepository.findAll(predicateFactory.forList(ownerId, filter, evaluatedAt), pageable)
                .map(booking -> mapper.toListItem(booking, evaluatedAt));
    }

    @Override
    public BookingSummaryResponse getOwnerBookingSummary(OwnerBookingFilter filter) {
        validateWindow(filter);
        UUID ownerId = currentProfileProvider.requireProfileId();
        Instant evaluatedAt = applicationClock.instant();
        scopeValidator.validate(ownerId, filter.stationId(), null, filter.connectorId());
        var bookings = readRepository.findAll(
                predicateFactory.forList(ownerId, filter, evaluatedAt), Pageable.unpaged()).getContent();
        long pending = 0, confirmed = 0, inSession = 0, completed = 0, cancelled = 0, expired = 0, noShow = 0;
        for (Booking booking : bookings) {
            var state = effectiveStatePolicy.evaluate(booking, evaluatedAt);
            switch (state.effectiveStatus()) {
                case PENDING -> pending++;
                case CONFIRMED -> confirmed++;
                case CHECKED_IN, CHARGING -> inSession++;
                case COMPLETED -> completed++;
                case CANCELLED -> cancelled++;
                case EXPIRED -> expired++;
            }
            if (state.cancellationReason() == com.thang.chargeops.booking.service.model.BookingReadEvaluation.CancellationReason.NO_SHOW) noShow++;
        }
        return new BookingSummaryResponse(bookings.size(), pending, confirmed, inSession,
                completed, cancelled, expired, noShow);
    }

    @Override
    public Page<OperationalBookingResponse> getOwnerActiveBookingsFor(
            OwnerActiveBookingFilter filter, int page, int size) {
        validatePaging(page, size);
        UUID ownerId = currentProfileProvider.requireProfileId();
        Instant evaluatedAt = applicationClock.instant();
        scopeValidator.validate(ownerId, filter.stationId(), filter.chargePointId(), filter.connectorId());
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
        return readRepository.findAll(predicateFactory.forActive(ownerId, filter, evaluatedAt), pageable)
                .map(booking -> mapper.toOperational(booking, evaluatedAt));
    }

    @Override
    public OwnerBookingDetailResponse getOwnerBooking(UUID bookingId) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        Instant evaluatedAt = applicationClock.instant();
        Booking booking = readRepository.findOwnerDetail(bookingId, ownerId).orElseGet(() -> {
            if (readRepository.existsById(bookingId)) throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
            throw new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, bookingId);
        });
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(CommonErrorCode.DATA_INTEGRITY_ERROR,
                        "Booking exists but payment was not found: " + bookingId));
        var snapshot = financialReadAssembler.assemble(booking, payment, evaluatedAt);
        return mapper.toDetail(booking, payment, snapshot, evaluatedAt);
    }

    private void validatePaging(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new AppException(CommonErrorCode.INVALID_REQUEST);
    }

    private void validateWindow(OwnerBookingFilter filter) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST, "from must be before to");
        }
    }
}
