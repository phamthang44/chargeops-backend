package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingHoldCoordinator {

    private final UserProfileRepository userProfileRepository;
    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final Clock applicationClock;

    /**
     * Chuẩn bị và kiểm tra điều kiện hold dưới khóa bi quan.
     * Phương thức bắt buộc chạy trong transaction ngoài cùng của caller (Propagation.MANDATORY).
     * Thứ tự khóa: Actor (Driver) -> Connector.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HoldPreparationContext prepareUnderLock(
            UserProfile lockedDriver,
            UUID connectorId,
            Instant startAt,
            Instant endAt
    ) {
        UUID driverId = lockedDriver.getId();
        // 1. Khóa Actor (Driver) để tuần tự hóa các thao tác tạo booking của tài xế này


        // 2. Khóa Connector để tuần tự hóa các yêu cầu giữ chỗ trên cùng một cổng
        Connector connector = connectorRepository.findByIdWithLock(connectorId)
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND));

        // 3. Đọc mốc thời gian Clock duy nhất SAU KHI đã giữ đủ các khóa cần thiết
        Instant decisionAt = applicationClock.instant();

        // 4. Kiểm tra giới hạn D-ABUSE (tối đa 1 booking PENDING còn hiệu lực per Driver)
        if (bookingRepository.existsActivePendingByDriver(driverId, decisionAt)) {
            throw new AppException(BookingErrorCode.PENDING_LIMIT_EXCEEDED);
        }

        // 5. Tự động chuyển PENDING quá hạn sang EXPIRED dưới lock (Self-Healing)
        List<Booking> overdueBookings = bookingRepository
                .findOverduePendingByConnectorForUpdate(connectorId, decisionAt);
        for (Booking overdue : overdueBookings) {
            bookingStatusHistoryRecorder.recordSystemTransition(
                    overdue,
                    BookingStatusReason.HOLD_EXPIRED,
                    decisionAt,
                    Booking::expire
            );
        }

        // 6. Kiểm tra giao thoa lịch (Overlap) theo khoảng nửa mở [startAt, endAt)
        if (bookingRepository.existsOverlappingBooking(connectorId, startAt, endAt, decisionAt)) {
            throw new AppException(BookingErrorCode.SLOT_UNAVAILABLE);
        }

        return new HoldPreparationContext(lockedDriver, connector, decisionAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserProfile lockDriver(UUID driverId) {
        return userProfileRepository.findByIdWithLock(driverId)
                .orElseThrow(() ->
                        new AppException(ProfileErrorCode.PROFILE_NOT_FOUND)
                );
    }
}
