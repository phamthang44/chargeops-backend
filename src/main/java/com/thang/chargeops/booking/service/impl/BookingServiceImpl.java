package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.ConfirmCheckInCanonicalPayload;
import com.thang.chargeops.booking.checkin.CheckInChallengeService;
import com.thang.chargeops.booking.checkin.ResolvedCheckInChallenge;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.request.ConfirmCheckInRequest;
import com.thang.chargeops.booking.dto.request.ResolveCheckInRequest;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.mapper.BookingPriceLineMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.pricing.PricePreview;
import com.thang.chargeops.booking.projection.BookingCompletedSessionProjection;
import com.thang.chargeops.booking.projection.BookingCheckInRouteProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.repository.specs.BookingSpecification;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.booking.service.BookingCheckoutPersistence;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.booking.service.DriverBookingDetailAssembler;
import com.thang.chargeops.booking.service.model.CanonicalPayload;
import com.thang.chargeops.booking.service.model.DriverBookingHistoryResult;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.gateway.PaymentGatewayRegistry;
import com.thang.chargeops.payment.gateway.PaymentGatewayProfile;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.CheckInPolicy;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
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

    private final BookingRepository bookingRepository;
    private final BookingPricingService bookingPricingService;
    private final BookingHoldCoordinator bookingHoldCoordinator;
    private final CurrentProfileProvider currentProfileProvider;
    private final BookingCommandRegistry bookingCommandRegistry;
    private final BookingMapper bookingMapper;
    private final BookingPolicyConfig bookingPolicyConfig;
    private final PaymentRepository paymentRepository;
    private final DriverBookingDetailAssembler driverBookingDetailAssembler;
    /** Retained as a constructor dependency for compatibility with existing booking wiring/tests. */
    private final DriverBookingReadPolicy driverBookingReadPolicy;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final BookingCheckoutPersistence bookingCheckoutPersistence;
    private final BookingCommandInFlightLock bookingCommandInFlightLock;
    private final CheckInChallengeService checkInChallengeService;
    private final ConnectorRepository connectorRepository;
    private final CheckInPolicy checkInPolicy;
    private final EquipmentStatusHistoryService equipmentStatusHistoryService;
    private final Clock applicationClock;

    @Transactional
    @Override
    public CreateBookingResponse createNewBooking(UUID requestKey, CreateBookingRequest request) {
        PaymentGatewayProfile gatewayProfile = paymentGatewayRegistry.profile(request.paymentMethod());
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
                        gatewayProfile.provider(),
                        gatewayProfile.receivingAccountRef(),
                        gatewayProfile.currency(),
                        gatewayProfile.environment()
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
    public CheckoutResponse createCheckout(UUID bookingId, UUID requestKey) {
        UserProfile driver = currentProfileProvider.requireProfile();
        String payloadHash = BookingCommandPayloadHasher.sha256(
                "CHECKOUT:" + bookingId
        );
        Instant evaluatedAt = applicationClock.instant();

        return bookingCommandInFlightLock.executeWithLock(
                driver.getId(),
                BookingCommandOperation.CREATE_CHECKOUT,
                requestKey,
                () -> {
                    Optional<UUID> replay = bookingCommandRegistry.findReplay(
                            driver.getId(),
                            BookingCommandOperation.CREATE_CHECKOUT,
                            requestKey,
                            payloadHash
                    );
                    if (replay.isPresent()) {
                        return bookingCheckoutPersistence.loadReplay(
                                replay.get(),
                                driver.getId(),
                                evaluatedAt
                        );
                    }

                    Payment payment = bookingCheckoutPersistence.prepareCheckout(
                            bookingId,
                            driver.getId(),
                            evaluatedAt
                    );
                    OrderCheckout checkout = payment.getProviderOrderRef() == null
                            ? paymentGatewayRegistry.createCheckout(payment, evaluatedAt)
                            : null;

                    return bookingCheckoutPersistence.completeCheckout(
                            bookingId,
                            driver.getId(),
                            requestKey,
                            payloadHash,
                            checkout,
                            applicationClock.instant()
                    );
                }
        );
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

        return driverBookingDetailAssembler.assemble(
                booking,
                payment,
                evaluatedAt
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

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponse getMyBookingStats() {
        UserProfile driver = currentProfileProvider.requireProfile();
        UUID driverId = driver.getId();
        Instant evaluatedAt = applicationClock.instant();

        List<BookingCompletedSessionProjection> completedSessions =
                bookingRepository.findCompletedSessionsByDriverId(driverId);

        BigDecimal totalSpending = paymentRepository.sumNetPaidAmountByDriverId(driverId);

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

    @Override
    @Transactional(readOnly = true)
    public ResolveCheckInResponse resolveCheckIn(ResolveCheckInRequest request) {
        UserProfile driver = currentProfileProvider.requireProfile();
        Booking booking = requireDriverBooking(request.bookingId(), driver.getId());
        ResolvedCheckInChallenge challenge = checkInChallengeService
                .resolveWithExpiry(request.challengeToken());
        Connector connector = connectorRepository.findById(challenge.connectorId())
                .orElseThrow(() -> new AppException(
                        StationErrorCode.CONNECTOR_NOT_FOUND,
                        challenge.connectorId()
                ));
        checkInPolicy.requireCanCheckIn(
                driver,
                booking,
                connector,
                applicationClock.instant()
        );

        return ResolveCheckInResponse.builder()
                .bookingId(booking.getId())
                .connectorId(connector.getId())
                .connectorCode(connector.getConnectorCode())
                .challengeExpiresAt(challenge.expiresAt())
                .startAt(booking.getStartAt())
                .endAt(booking.getEndAt())
                .checkInDeadline(booking.getCheckInDeadline())
                .expectedVersion(booking.getVersion())
                .build();
    }

    @Override
    @Transactional
    public BookingDetailResponse confirmCheckIn(
            UUID bookingId,
            UUID requestKey,
            ConfirmCheckInRequest request
    ) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(request, "request must not be null");
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "confirmCheckIn", bookingId, requestKey);

        UserProfile driver = currentProfileProvider.requireProfile();

        String payloadHash = BookingCommandPayloadHasher.sha256(
                ConfirmCheckInCanonicalPayload.builder()
                        .bookingId(bookingId)
                        .expectedVersion(request.expectedVersion())
                        .challengeToken(request.challengeToken())
                        .build()
        );

        Optional<UUID> fastReplay = bookingCommandRegistry.findReplay(
                driver.getId(),
                BookingCommandOperation.CONFIRM_CHECK_IN,
                requestKey,
                payloadHash
        );
        if (fastReplay.isPresent()) {
            return loadReplayBookingDetail(fastReplay.get());
        }

        UserProfile lockedDriver = bookingHoldCoordinator.lockDriver(driver.getId());

        Optional<UUID> lockedReplay = bookingCommandRegistry.findReplay(
                lockedDriver.getId(),
                BookingCommandOperation.CONFIRM_CHECK_IN,
                requestKey,
                payloadHash
        );
        if (lockedReplay.isPresent()) {
            return loadReplayBookingDetail(lockedReplay.get());
        }

        BookingCheckInRouteProjection route = bookingRepository.findCheckInRouteById(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found: " + bookingId
                ));
        if (!Objects.equals(route.getDriverId(), lockedDriver.getId())) {
            throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
        }

        Connector lockedConnector = connectorRepository.findByIdWithLock(route.getConnectorId())
                .orElseThrow(() -> new AppException(
                        StationErrorCode.CONNECTOR_NOT_FOUND,
                        route.getConnectorId()
                ));
        Booking lockedBooking = bookingRepository
                .findByIdAndDriverIdWithLock(bookingId, lockedDriver.getId())
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found: " + bookingId
                ));
        Payment lockedPayment = paymentRepository.findByBookingIdWithLock(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Payment not found for booking: " + bookingId
                ));

        Instant decisionAt = applicationClock.instant();

        if (!Objects.equals(lockedBooking.getVersion(), request.expectedVersion())) {
            throw new AppException(
                    BookingErrorCode.STATE_CONFLICT,
                    "Booking version changed"
            );
        }

        checkInPolicy.requireCanCheckIn(
                lockedDriver,
                lockedBooking,
                lockedConnector,
                decisionAt
        );

        // Redis compare-and-delete is the last guard before DB mutation. It is
        // intentionally not compensated if the surrounding DB transaction rolls back.
        checkInChallengeService.validateAndConsume(
                request.challengeToken(),
                lockedConnector.getId()
        );

        BookingCommand command = bookingCommandRegistry.recordSuccess(
                lockedDriver,
                BookingCommandOperation.CONFIRM_CHECK_IN,
                requestKey,
                payloadHash,
                lockedBooking,
                decisionAt
        );

        bookingStatusHistoryRecorder.recordUserTransition(
                command,
                BookingStatusActorType.DRIVER,
                BookingStatusReason.CHECK_IN_CONFIRMED,
                decisionAt,
                booking -> booking.checkIn(decisionAt)
        );

        equipmentStatusHistoryService.transitionConnectorRuntimeAsSystem(
                lockedConnector,
                RuntimeStatus.IN_USE,
                decisionAt,
                "BOOKING_CHECK_IN:" + bookingId
        );

        bookingRepository.flush();

        return driverBookingDetailAssembler.assemble(
                lockedBooking,
                lockedPayment,
                decisionAt
        );
    }

    private BookingDetailResponse loadReplayBookingDetail(UUID bookingId) {
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
        return driverBookingDetailAssembler.assemble(
                booking,
                payment,
                applicationClock.instant()
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
