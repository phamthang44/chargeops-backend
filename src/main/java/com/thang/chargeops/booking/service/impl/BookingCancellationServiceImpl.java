package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.CancelBookingCanonicalPayload;
import com.thang.chargeops.booking.dto.request.CancelBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingCancellationReason;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationRefundContext;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.booking.projection.BookingCancellationRouteProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingCancellationService;
import com.thang.chargeops.booking.service.DriverBookingDetailAssembler;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingCancellationServiceImpl implements BookingCancellationService {

    private final CurrentProfileProvider currentProfileProvider;
    private final UserProfileRepository userProfileRepository;
    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingCommandRegistry bookingCommandRegistry;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final BookingCancellationPolicy bookingCancellationPolicy;
    private final RefundObligationService refundObligationService;
    private final DriverBookingDetailAssembler driverBookingDetailAssembler;
    private final Clock applicationClock;

    @Override
    @Transactional
    public BookingDetailResponse cancelBooking(
            UUID bookingId,
            UUID requestKey,
            CancelBookingRequest request
    ) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(request, "request must not be null");

        UserProfile driver = currentProfileProvider.requireProfile();

        // 1. Build canonical payload hash (including bookingId as path parameter identity)
        CancelBookingCanonicalPayload canonicalPayload = CancelBookingCanonicalPayload.builder()
                .bookingId(bookingId)
                .expectedVersion(request.expectedVersion())
                .expectedRefundAmount(request.expectedRefundAmount())
                .acceptedPolicyVersion(request.acceptedPolicyVersion())
                .build();
        String payloadHash = BookingCommandPayloadHasher.sha256(canonicalPayload);

        // 2. Fast replay before lock
        Optional<UUID> fastReplay = bookingCommandRegistry.findReplay(
                driver.getId(),
                BookingCommandOperation.CANCEL_BOOKING,
                requestKey,
                payloadHash
        );
        if (fastReplay.isPresent()) {
            return loadReplayDetail(fastReplay.get());
        }

        // 3. Lock Actor (Driver)
        UserProfile lockedDriver = userProfileRepository.findByIdWithLock(driver.getId())
                .orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));

        // 4. Locked replay under Actor lock (closing concurrent retry race)
        Optional<UUID> lockedReplay = bookingCommandRegistry.findReplay(
                lockedDriver.getId(),
                BookingCommandOperation.CANCEL_BOOKING,
                requestKey,
                payloadHash
        );
        if (lockedReplay.isPresent()) {
            return loadReplayDetail(lockedReplay.get());
        }

        // 5. Scalar booking route projection (avoiding L1 entity preload before connector lock)
        BookingCancellationRouteProjection route = bookingRepository.findCancellationRouteById(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found: " + bookingId
                ));

        if (!Objects.equals(route.getDriverId(), lockedDriver.getId())) {
            throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
        }

        // 6. Lock Connector -> Booking -> Payment in strict hierarchy
        Connector lockedConnector = connectorRepository.findByIdWithLock(route.getConnectorId())
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND));

        Booking lockedBooking = bookingRepository.findByIdAndDriverIdWithLock(bookingId, lockedDriver.getId())
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found: " + bookingId
                ));

        Payment lockedPayment = paymentRepository.findByBookingIdWithLock(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Payment not found for booking: " + bookingId
                ));

        // 7. Single server time taken after main locks are acquired
        Instant decisionAt = applicationClock.instant();

        // 8. Effective status & boundary guards
        BookingStatus status = lockedBooking.getStatus();
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED) {
            throw new AppException(
                    BookingErrorCode.STATE_CONFLICT,
                    "Booking cannot be cancelled in status: " + status
            );
        }

        if (status == BookingStatus.PENDING) {
            if (lockedBooking.getExpiresAt() != null && !decisionAt.isBefore(lockedBooking.getExpiresAt())) {
                throw new AppException(BookingErrorCode.HOLD_EXPIRED);
            }
        } else {
            // CONFIRMED
            if (lockedBooking.getCheckedInAt() != null) {
                throw new AppException(
                        BookingErrorCode.STATE_CONFLICT,
                        "Booking already checked in"
                );
            }
            if (lockedBooking.getCheckInDeadline() != null && !decisionAt.isBefore(lockedBooking.getCheckInDeadline())) {
                throw new AppException(BookingErrorCode.CHECK_IN_CLOSED);
            }
        }

        // 9. Server policy refund calculation
        BigDecimal refundablePackageAmount = status == BookingStatus.CONFIRMED
                ? lockedPayment.getAmount().subtract(
                        lockedPayment.getRefundAmount() != null
                                ? lockedPayment.getRefundAmount()
                                : BigDecimal.ZERO
                )
                : BigDecimal.ZERO;

        CancellationRefundContext context = new CancellationRefundContext(
                status,
                lockedBooking.getPaymentConfirmedAt(),
                lockedBooking.getStartAt(),
                lockedBooking.getCheckedInAt(),
                lockedBooking.getFreeCancellationDeadline(),
                refundablePackageAmount
        );

        CancellationRefundDecision decision = bookingCancellationPolicy.calculateRefund(
                context,
                decisionAt,
                false
        );
        long serverRefundAmount = decision.refundAmount().longValueExact();

        // 10. Compare consent (version, refund amount, policy version)
        boolean consentMatches = Objects.equals(lockedBooking.getVersion(), request.expectedVersion())
                && serverRefundAmount == request.expectedRefundAmount()
                && Objects.equals(lockedBooking.getPolicyVersion(), request.acceptedPolicyVersion());

        if (!consentMatches) {
            BookingDetailResponse currentBooking = driverBookingDetailAssembler.assemble(
                    lockedBooking,
                    lockedPayment,
                    decisionAt
            );
            throw AppException.withDetails(
                    BookingErrorCode.CANCELLATION_CHANGED,
                    Map.of("currentBooking", currentBooking)
            );
        }

        // 11. Resolve exact APPLIED receipt scalar ID if refundAmount > 0
        UUID sourceReceiptId = null;
        if (serverRefundAmount > 0) {
            if (lockedPayment.getStatus() != PaymentStatus.PAID || lockedPayment.getPaidAt() == null) {
                throw new AppException(
                        RefundErrorCode.EXECUTION_CONFLICT,
                        "Payment is not eligible for refund"
                );
            }
            List<UUID> appliedReceiptIds = paymentTransactionRepository.findAppliedReceiptIdsByPaymentId(lockedPayment.getId());
            if (appliedReceiptIds.isEmpty()) {
                throw new AppException(
                        RefundErrorCode.EXECUTION_CONFLICT,
                        "No applied receipt found for paid booking payment: " + lockedPayment.getId()
                );
            }
            sourceReceiptId = appliedReceiptIds.get(0);
        }

        // 12. Mutation order: recordSuccess -> recordUserTransition (callback booking.cancel)
        BookingCommand command = bookingCommandRegistry.recordSuccess(
                lockedDriver,
                BookingCommandOperation.CANCEL_BOOKING,
                requestKey,
                payloadHash,
                lockedBooking,
                decisionAt
        );

        bookingStatusHistoryRecorder.recordUserTransition(
                command,
                BookingStatusActorType.DRIVER,
                BookingStatusReason.DRIVER_CANCELLED,
                decisionAt,
                booking -> booking.cancel(BookingCancellationReason.DRIVER_CANCELLED.name(), decisionAt)
        );

        // 13. Create Refund obligation in the same transaction if eligible
        if (serverRefundAmount > 0) {
            CreateRefundObligationCommand refundCommand = new CreateRefundObligationCommand(
                    lockedBooking,
                    lockedPayment,
                    sourceReceiptId,
                    RefundBasisType.BOOKING_CANCELLATION,
                    command.getId(),
                    RefundReason.VOLUNTARY_GRACE,
                    lockedDriver,
                    decisionAt
            );
            refundObligationService.createObligation(refundCommand);
        }

        // 14. Explicit flush to increment optimistic version and persist refund before assembly
        bookingRepository.flush();

        // 15. Shared detail assembly for final response
        return driverBookingDetailAssembler.assemble(
                lockedBooking,
                lockedPayment,
                decisionAt
        );
    }

    private BookingDetailResponse loadReplayDetail(UUID replayedBookingId) {
        Booking booking = bookingRepository.findById(replayedBookingId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = paymentRepository.findByBookingId(replayedBookingId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return driverBookingDetailAssembler.assemble(
                booking,
                payment,
                applicationClock.instant()
        );
    }
}
