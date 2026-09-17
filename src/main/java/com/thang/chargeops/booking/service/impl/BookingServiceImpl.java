package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.mapper.BookingPriceLineMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.pricing.PricePreview;
import com.thang.chargeops.booking.projection.BookingCompletedSessionProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.repository.specs.BookingSpecification;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.booking.service.model.CanonicalPayload;
import com.thang.chargeops.booking.service.model.DriverBookingHistoryResult;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final String CURRENCY = "VND";
    private static final String SIMULATOR_PROVIDER = "SIMULATOR";
    private static final String SIMULATOR_ACCOUNT_REF = "SIMULATOR";

    private final BookingRepository bookingRepository;
    private final BookingPricingService bookingPricingService;
    private final BookingHoldCoordinator bookingHoldCoordinator;
    private final CurrentProfileProvider currentProfileProvider;
    private final BookingCommandRegistry bookingCommandRegistry;
    private final BookingMapper bookingMapper;
    private final BookingPolicyConfig bookingPolicyConfig;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DriverBookingReadPolicy driverBookingReadPolicy;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final Clock applicationClock;

    @Transactional
    @Override
    public CreateBookingResponse createNewBooking(UUID requestKey, CreateBookingRequest request) {
        //Với phạm vi hiện tại, nên chỉ hỗ trợ SIMULATOR.
        if (request.paymentMethod() != PaymentMethod.SIMULATOR) {
            throw new AppException(PaymentErrorCode.METHOD_INVALID);
        }
        UserProfile driver = currentProfileProvider.requireProfile();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "createNewBooking", driver.getId(), request);
        Instant requestedEndAt = request.startAt()
                .plus(Duration.ofMinutes(request.durationMin()));

        String payloadHash = hashRequest(request);

        // 1. Replay nhanh, chưa cần lock
        Optional<CreateBookingResponse> replay =
                findReplay(driver.getId(), requestKey, payloadHash);

        if (replay.isPresent()) {
            return replay.get();
        }

        // 2. Không có replay thì khóa Driver
        UserProfile lockedDriver =
                bookingHoldCoordinator.lockDriver(driver.getId());

        // 3. Kiểm tra lại sau khi đã khóa
        Optional<CreateBookingResponse> lockedReplay =
                findReplay(lockedDriver.getId(), requestKey, payloadHash);

        if (lockedReplay.isPresent()) {
            return lockedReplay.get();
        }

        // 4. Sau đó mới khóa Connector và kiểm tra slot
        HoldPreparationContext holdContext =
                bookingHoldCoordinator.prepareUnderLock(
                        lockedDriver,
                        request.connectorId(),
                        request.startAt(),
                        requestedEndAt
                );

        Instant decisionAt = holdContext.decisionAt();

        PricePreviewResponse latestPricePreview = bookingPricingService.repriceUnderLock(
                lockedDriver,
                holdContext.connector(),
                request.startAt(),
                request.durationMin(),
                decisionAt
        );

        requireConsentUnchanged(request, latestPricePreview);

        Booking booking = buildPendingBooking(
                request,
                requestKey,
                holdContext,
                latestPricePreview
        );
        Booking savedBooking = bookingRepository.save(booking);
        Payment payment = Payment.createPending(
                new PendingPaymentSpec(
                        savedBooking,
                        savedBooking.getTotalAmount(),
                        request.paymentMethod(),
                        SIMULATOR_PROVIDER,
                        SIMULATOR_ACCOUNT_REF,
                        CURRENCY
                )
        );
        Payment savedPayment = paymentRepository.save(payment);
        BookingCommand command = bookingCommandRegistry.recordSuccess(
                lockedDriver,
                BookingCommandOperation.CREATE_BOOKING,
                requestKey,
                payloadHash,
                savedBooking,
                decisionAt
        );

        bookingStatusHistoryRecorder.recordCreation(
                command,
                BookingStatusActorType.DRIVER,
                decisionAt
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "createNewBooking", savedBooking.getId(), request);
        return bookingMapper.toCreateBookingResponse(savedBooking, savedPayment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DriverBookingListItemResponse> getMyActiveBookings(int page, int size) {
        UserProfile driver = currentProfileProvider.requireProfile();
        Instant evaluatedAt = applicationClock.instant();

        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_START,
                "getMyActiveBookings",
                driver.getId(),
                null
        );

        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1),
                size
        );

        Page<Booking> bookings = bookingRepository.findActiveForDriver(
                driver.getId(),
                evaluatedAt,
                pageable
        );

        return bookings.map(booking ->
                bookingMapper.toDriverBookingListItemResponse(
                        booking,
                        evaluatedAt
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public DriverBookingHistoryResult getMyBookingHistory(
            DriverBookingHistoryFilter filter,
            int page,
            int size
    ) {

        UserProfile driver = currentProfileProvider.requireProfile();
        Instant evaluatedAt = applicationClock.instant();
        DriverBookingHistoryFilter normalizedFilter = filter == null
                ? new DriverBookingHistoryFilter(
                        "",
                        DriverBookingHistoryFilter.HistoryStatus.ALL
                )
                : filter;

        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_START,
                "getMyBookingHistory",
                driver.getId(),
                normalizedFilter
        );

        Specification<Booking> specification =
                BookingSpecification.historyForDriver(
                        driver.getId(),
                        normalizedFilter,
                        evaluatedAt
                );

        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1),
                size,
                Sort.by(
                        Sort.Order.desc("startAt"),
                        Sort.Order.desc("id")
                )
        );

        Page<DriverBookingListItemResponse> bookings = bookingRepository
                .findAll(specification, pageable)
                .map(booking ->
                        bookingMapper.toDriverBookingListItemResponse(
                                booking,
                                evaluatedAt
                        )
                );

        long completed = bookingRepository.count(
                BookingSpecification.historyForDriver(
                        driver.getId(),
                        normalizedFilter.withStatus(
                                DriverBookingHistoryFilter.HistoryStatus.COMPLETED
                        ),
                        evaluatedAt
                )
        );
        long cancelled = bookingRepository.count(
                BookingSpecification.historyForDriver(
                        driver.getId(),
                        normalizedFilter.withStatus(
                                DriverBookingHistoryFilter.HistoryStatus.CANCELLED
                        ),
                        evaluatedAt
                )
        );

        return new DriverBookingHistoryResult(
                bookings,
                Map.of(
                        "all", completed + cancelled,
                        "completed", completed,
                        "cancelled", cancelled
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public BookingDetailResponse getMyBooking(UUID bookingId) {
        UserProfile driver = currentProfileProvider.requireProfile();
        Instant evaluatedAt = applicationClock.instant();
        Booking booking = requireDriverBooking(bookingId, driver.getId());

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking exists but payment was not found: " + bookingId
                ));

        BookingReadSnapshot snapshot = buildBookingReadSnapshot(
                booking,
                payment,
                evaluatedAt
        );

        return bookingMapper.toBookingDetailResponse(
                booking,
                payment,
                snapshot
        );
    }

    private Booking requireDriverBooking(UUID bookingId, UUID driverId) {
        Optional<Booking> booking = bookingRepository.findByIdAndDriverId(
                bookingId,
                driverId
        );
        if (booking.isPresent()) {
            return booking.get();
        }
        if (bookingRepository.existsById(bookingId)) {
            throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
        }
        throw new AppException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                "Booking not found: " + bookingId
        );
    }

    /**
     * Aggregates the request-time facts required by the Driver detail mapper.
     * Repository access stays in the service; the mapper remains deterministic.
     */
    private BookingReadSnapshot buildBookingReadSnapshot(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        BookingReadSnapshot listSnapshot = driverBookingReadPolicy
                .snapshotForList(booking, evaluatedAt);
        List<PaymentTransaction> receipts = paymentTransactionRepository
                .findByPaymentIdOrderByReceivedAtAscIdAsc(
                        Objects.requireNonNull(
                                payment.getId(),
                                "payment id must not be null"
                        )
                );

        return BookingReadSnapshot.builder()
                .evaluatedAt(listSnapshot.evaluatedAt())
                .stationAvailable(listSnapshot.stationAvailable())
                .canReportIssue(listSnapshot.canReportIssue())
                .currency(payment.getCurrency())
                .collectedAmount(sumReceiptAmounts(receipts, null))
                .appliedToPackageAmount(sumReceiptAmounts(
                        receipts,
                        PaymentApplicationClassification.APPLIED
                ))
                .packageRefundedAmount(amountOrZero(
                        payment.getRefundAmount()
                ))
                // Order VA accepts the exact package amount. Receipts that
                // cannot be applied remain UNAPPLIED; they are not silently
                // reclassified as excess money.
                .excessAmount(0L)
                .unallocatedAmount(sumReceiptAmounts(
                        receipts,
                        PaymentApplicationClassification.UNAPPLIED
                ))
                .checkout(toCheckoutDetail(payment, evaluatedAt))
                // Refund obligations/attempts are introduced by later tasks.
                // refundAmount above only represents successful package refund.
                .refunds(List.of())
                .build();
    }

    private long sumReceiptAmounts(
            List<PaymentTransaction> receipts,
            PaymentApplicationClassification classification
    ) {
        return receipts.stream()
                .filter(receipt -> classification == null
                        || receipt.getApplicationClassification()
                        == classification)
                .map(PaymentTransaction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .longValueExact();
    }

    private long amountOrZero(BigDecimal amount) {
        return amount == null ? 0L : amount.longValueExact();
    }

    private BookingDetailResponse.CheckoutDetail toCheckoutDetail(
            Payment payment,
            Instant evaluatedAt
    ) {
        BookingDetailResponse.CheckoutState state;
        if (payment.getProviderOrderRef() == null) {
            state = BookingDetailResponse.CheckoutState.NOT_CREATED;
        } else if (payment.getProviderExpiresAt() == null) {
            state = BookingDetailResponse.CheckoutState.UNAVAILABLE;
        } else if (!evaluatedAt.isBefore(payment.getProviderExpiresAt())) {
            state = BookingDetailResponse.CheckoutState.EXPIRED;
        } else {
            state = BookingDetailResponse.CheckoutState.READY;
        }

        return new BookingDetailResponse.CheckoutDetail(
                state,
                payment.getMethod(),
                payment.getProviderExpiresAt(),
                null,
                payment.getProviderOrderRef(),
                payment.getQrCodeUrl()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponse getMyBookingStats() {
        UserProfile driver = currentProfileProvider.requireProfile();
        UUID driverId = driver.getId();
        Instant evaluatedAt = applicationClock.instant();

        List<BookingCompletedSessionProjection> completedSessions =
                bookingRepository.findCompletedSessionsByDriverId(driverId);

        BigDecimal totalSpending = completedSessions.stream()
                .map(BookingCompletedSessionProjection::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalCompleted = completedSessions.size();

        long totalMinutes = completedSessions.stream()
                .filter(s -> s.getStartAt() != null && s.getEndAt() != null)
                .mapToLong(s -> Duration.between(s.getStartAt(), s.getEndAt()).toMinutes())
                .sum();

        double totalHours = Math.round((totalMinutes / 60.0) * 10.0) / 10.0;

        long totalBookings = bookingRepository.countByDriverId(driverId);

        long totalCancelled = bookingRepository.count(
                BookingSpecification.historyForDriver(
                        driverId,
                        new DriverBookingHistoryFilter(
                                "",
                                DriverBookingHistoryFilter.HistoryStatus.CANCELLED
                        ),
                        evaluatedAt
                )
        );

        return BookingStatsResponse.of(
                totalSpending,
                totalCompleted,
                totalBookings,
                totalCompleted,
                totalCancelled,
                totalHours
        );
    }

    private CreateBookingResponse loadReplayBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking command exists but booking was not found: " + bookingId
                ));

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking exists but payment was not found: " + bookingId
                ));

        return bookingMapper.toCreateBookingResponse(booking, payment);
    }

    private String hashRequest(CreateBookingRequest request) {
        CanonicalPayload payload = CanonicalPayload.builder()
                .connectorId(request.connectorId())
                .startAt(request.startAt())
                .durationMin(request.durationMin())
                .acceptedTotalAmount(request.acceptedTotalAmount())
                .acceptedPricingVersion(request.acceptedPricingVersion())
                .acceptedPolicyVersion(request.acceptedPolicyVersion())
                .paymentMethod(request.paymentMethod())
                .build();

        return BookingCommandPayloadHasher.sha256(payload);
    }

    private Optional<CreateBookingResponse> findReplay(
            UUID driverId,
            UUID requestKey,
            String payloadHash
    ) {
        return bookingCommandRegistry.findReplay(
                driverId,
                BookingCommandOperation.CREATE_BOOKING,
                requestKey,
                payloadHash
        ).map(this::loadReplayBooking);
    }

    private void requireConsentUnchanged(
            CreateBookingRequest request,
            PricePreviewResponse currentPrice
    ) {
        boolean changed =
                request.acceptedTotalAmount() != currentPrice.totalAmount()
                        || !request.acceptedPricingVersion()
                        .equals(currentPrice.pricingVersion())
                        || !request.acceptedPolicyVersion()
                        .equals(currentPrice.policy().policyVersion());

        if (changed) {
            throw AppException.withDetails(
                    BookingErrorCode.PRICE_CHANGED,
                    Map.of("latestPricePreview", currentPrice)
            );
        }
    }

    private Booking buildPendingBooking(
            CreateBookingRequest request,
            UUID requestKey,
            HoldPreparationContext holdContext,
            PricePreviewResponse pricePreview
    ) {
        Instant expiresAt = holdContext.decisionAt().plus(
                Duration.ofMinutes(
                        bookingPolicyConfig.getPaymentHoldMinutes()
                )
        );

        Connector connector = holdContext.connector();
        ChargePoint chargePoint = connector.getChargePoint();
        Station station = chargePoint.getStation();

        BookingPolicySnapshot policySnapshot =
                BookingPolicySnapshot.from(pricePreview.policy());

        PricePreview currentPrice = new PricePreview(
                pricePreview.totalAmount(),
                pricePreview.priceLines(),
                pricePreview.pricingBasis()
        );

        List<BookingPriceLine> priceLines =
                BookingPriceLineMapper.toEntities(currentPrice);

        Booking booking = Booking.createPending(
                Booking.PendingBookingSpec.builder()
                        .driver(holdContext.driver())
                        .connector(connector)
                        .startAt(request.startAt())
                        .endAt(pricePreview.endAt())
                        .expiresAt(expiresAt)
                        .totalAmount(
                                BigDecimal.valueOf(pricePreview.totalAmount())
                        )
                        .bookingCode(generateBookingCode(
                                holdContext.driver().getId(), requestKey))
                        .policyVersion(policySnapshot.policyVersion())
                        .policySnapshot(policySnapshot)
                        .checkInDeadline(
                                pricePreview.endAt().minus(
                                        Duration.ofMinutes(
                                                policySnapshot.checkInCloseBeforeEndMin()
                                        )
                                )
                        )
                        .stationNameSnapshot(station.getName())
                        .stationAddressSnapshot(station.getAddressLine())
                        .chargePointCodeSnapshot(chargePoint.getChargePointCode())
                        .connectorCodeSnapshot(connector.getConnectorCode())
                        .build()
        );

        priceLines.forEach(booking::addPriceLine);
        return booking;
    }

    private String generateBookingCode(UUID driverId, UUID requestKey) {
        String digest = BookingCommandPayloadHasher.sha256(
                driverId + ":" + requestKey
        );
        return "BK-" + digest.substring(0, 20).toUpperCase(Locale.ROOT);
    }
}
