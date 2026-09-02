package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.policy.ConnectorOperationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ConnectorOperationPolicyImpl implements ConnectorOperationPolicy {

    private static final Set<BookingStatus> BLOCKING_BOOKING_STATUSES =
            EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.CHECKED_IN);

    private final BookingRepository bookingRepository;

    @Override
    public void requireCanChangeRuntimeStatus(
            Connector connector,
            RuntimeStatus targetStatus,
            String reason
    ) {
        if (targetStatus == null) {
            throw new AppException(StationErrorCode.CONNECTOR_RUNTIME_STATUS_REQUIRED);
        }

        ChargePoint chargePoint = connector.getChargePoint();
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
        if (targetStatus == RuntimeStatus.IN_USE
                || connector.getRuntimeStatus() == RuntimeStatus.IN_USE) {
            throw new AppException(StationErrorCode.CONNECTOR_RUNTIME_STATUS_SYSTEM_MANAGED);
        }
        if (targetStatus == RuntimeStatus.OFFLINE && (reason == null || reason.isBlank())) {
            throw new AppException(StationErrorCode.CONNECTOR_STATUS_REASON_REQUIRED);
        }
        if (targetStatus == RuntimeStatus.OFFLINE
                && bookingRepository.existsByConnectorIdAndStatusIn(
                        connector.getId(),
                        BLOCKING_BOOKING_STATUSES
                )) {
            throw new AppException(StationErrorCode.CONNECTOR_HAS_ACTIVE_BOOKINGS);
        }
    }
}
