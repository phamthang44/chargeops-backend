package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.CheckInPolicy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link CheckInPolicy} complying with:
 * - BR-BOK-04: Cửa sổ check-in từ startAt (inclusive) đến trước (endAt - 15 phút) (exclusive).
 * - BR-BOK-05: Đến mốc cutoff (endAt - 15 phút) không được check-in (chuẩn bị chuyển no-show).
 * - BR-STA-05: License hết hạn không cấm check-in các booking đã CONFIRMED nếu hạ tầng vẫn phục vụ được.
 * - BR-CHG-01/BR-STA-01: Runtime hạ tầng trạm/trụ/cổng phải sẵn sàng phục vụ; lỗi ném STATION_UNAVAILABLE.
 */
@Component
public class CheckInPolicyImpl implements CheckInPolicy {

    @Override
    public void requireCanCheckIn(UserProfile driver, Booking booking, Connector connector, Instant at) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(connector, "connector must not be null");
        Objects.requireNonNull(at, "evaluated time 'at' must not be null");

        // 1. Booking ownership: Phải đúng tài xế sở hữu booking
        if (booking.getDriver() == null
                || !samePersistedId(booking.getDriver().getId(), driver.getId())) {
            throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
        }

        // 2. Booking state: Chỉ booking CONFIRMED mới được phép check-in
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new AppException(BookingErrorCode.STATE_CONFLICT);
        }

        // 3. Đúng Connector: Súng sạc check-in phải khớp với súng sạc đã đặt
        if (booking.getConnector() == null
                || !samePersistedId(booking.getConnector().getId(), connector.getId())) {
            throw new AppException(BookingErrorCode.CONNECTOR_MISMATCH, connector.getConnectorCode());
        }

        // 4. Check-in window (BR-BOK-04): [startAt (inclusive), checkInDeadline (exclusive))
        Instant startAt = booking.getStartAt();
        if (startAt == null) {
            throw new AppException(BookingErrorCode.STATE_CONFLICT);
        }
        if (at.isBefore(startAt)) {
            throw new AppException(BookingErrorCode.CHECK_IN_TOO_EARLY);
        }

        // Bắt buộc dùng snapshot đã chốt lúc tạo booking; không tính lại từ cấu hình hiện hành.
        Instant deadline = booking.getCheckInDeadline();
        if (deadline == null) {
            throw new AppException(BookingErrorCode.STATE_CONFLICT);
        }

        if (!at.isBefore(deadline)) {
            // Đúng tại cutoff hoặc sau cutoff đều không được check-in
            throw new AppException(BookingErrorCode.CHECK_IN_CLOSED);
        }

        // 5. Runtime hạ tầng có thể dùng (Serviceable / STATION_UNAVAILABLE)
        // BR-STA-05: TUYỆT ĐỐI KHÔNG re-check License của Station!
        if (!isHardwareServiceable(connector)) {
            throw new AppException(BookingErrorCode.STATION_UNAVAILABLE);
        }
    }

    private boolean isHardwareServiceable(Connector connector) {
        ChargePoint chargePoint = connector.getChargePoint();
        if (chargePoint == null) {
            return false;
        }
        Station station = chargePoint.getStation();
        if (station == null) {
            return false;
        }

        return station.getStatus() == StationStatus.ACTIVE
                && station.getOperationalStatus() == StationOperationalStatus.OPERATING
                && chargePoint.getProvisioningStatus() == ProvisioningStatus.ACTIVE
                && chargePoint.getOperationalChargePointStatus() == OperationalChargePointStatus.AVAILABLE
                && connector.getRuntimeStatus() == RuntimeStatus.AVAILABLE;
    }

    private boolean samePersistedId(UUID expected, UUID actual) {
        return expected != null && expected.equals(actual);
    }
}
