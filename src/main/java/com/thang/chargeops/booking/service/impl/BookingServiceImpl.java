package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.mapper.BookingPriceLineMapper;
import com.thang.chargeops.booking.pricing.PricePreview;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.booking.service.model.CanonicalPayload;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;

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
