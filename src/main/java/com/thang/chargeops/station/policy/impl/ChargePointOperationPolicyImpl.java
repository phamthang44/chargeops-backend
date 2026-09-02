package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.policy.ChargePointOperationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ChargePointOperationPolicyImpl implements ChargePointOperationPolicy {

    private static final Set<BookingStatus> BLOCKING_BOOKING_STATUSES =
            EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.CHECKED_IN);

    private final BookingRepository bookingRepository;

    @Override
    public void requireCanChangeOperationalStatus(
            ChargePoint chargePoint,
            OperationalChargePointStatus targetStatus,
            String reason
    ) {
        if (targetStatus == null) {
            throw new AppException(StationErrorCode.CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED);
        }
        if (chargePoint.getProvisioningStatus() == ProvisioningStatus.SUSPENDED) {
            throw new AppException(StationErrorCode.CHARGE_POINT_SUSPENDED);
        }
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.ACTIVE) {
            throw new AppException(
                    StationErrorCode.INVALID_CHARGE_POINT_PROVISIONING_TRANSITION,
                    chargePoint.getProvisioningStatus(),
                    ProvisioningStatus.ACTIVE
            );
        }
        if (targetStatus != OperationalChargePointStatus.AVAILABLE
                && (reason == null || reason.isBlank())) {
            throw new AppException(StationErrorCode.CHARGE_POINT_STATUS_REASON_REQUIRED);
        }
        if (targetStatus != OperationalChargePointStatus.AVAILABLE
                && bookingRepository.existsByConnectorChargePointIdAndStatusIn(
                        chargePoint.getId(),
                        BLOCKING_BOOKING_STATUSES
                )) {
            throw new AppException(StationErrorCode.CHARGE_POINT_HAS_ACTIVE_BOOKINGS);
        }
    }
}
