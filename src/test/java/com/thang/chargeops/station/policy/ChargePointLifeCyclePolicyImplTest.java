package com.thang.chargeops.station.policy;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.impl.ChargePointOperationPolicyImpl;
import com.thang.chargeops.station.policy.impl.ChargePointProvisioningPolicyImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChargePointLifeCyclePolicyImplTest {

    @Mock
    private BookingRepository bookingRepository;

    private ChargePointProvisioningPolicyImpl provisioningPolicy;
    private ChargePointOperationPolicyImpl operationPolicy;

    @BeforeEach
    void setUp() {
        provisioningPolicy = new ChargePointProvisioningPolicyImpl();
        operationPolicy = new ChargePointOperationPolicyImpl(bookingRepository);
    }

    @Test
    void provisioningRequiresAnActiveStation() {
        Station station = mock(Station.class);
        when(station.getStatus()).thenReturn(StationStatus.PENDING_APPROVAL);

        assertCode(
                () -> provisioningPolicy.requireCanProvision(station),
                StationErrorCode.STATION_NOT_ELIGIBLE_FOR_PROVISIONING
        );
    }

    @Test
    void activationRequiresAtLeastOneConnector() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        Station station = mock(Station.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.PENDING_ACTIVATION);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(chargePoint.hasConnectors()).thenReturn(false);

        assertCode(
                () -> provisioningPolicy.requireCanActivate(chargePoint, 1),
                StationErrorCode.CHARGE_POINT_REQUIRES_CONNECTOR
        );
    }

    @Test
    void activationRequiresConfirmedConnectorCountToMatchInventory() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        Station station = mock(Station.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.PENDING_ACTIVATION);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(chargePoint.hasConnectors()).thenReturn(true);
        when(chargePoint.getConnectors()).thenReturn(java.util.List.of(mock(com.thang.chargeops.station.entity.Connector.class)));

        assertCode(
                () -> provisioningPolicy.requireCanActivate(chargePoint, 2),
                StationErrorCode.CHARGE_POINT_CONNECTOR_COUNT_MISMATCH
        );
    }

    @Test
    void ownerCannotOverrideAdminSuspension() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.SUSPENDED);

        assertCode(
                () -> operationPolicy.requireCanChangeOperationalStatus(
                        chargePoint,
                        OperationalChargePointStatus.AVAILABLE,
                        null
                ),
                StationErrorCode.CHARGE_POINT_SUSPENDED
        );
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void takingChargePointOfflineRequiresReason() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);

        assertCode(
                () -> operationPolicy.requireCanChangeOperationalStatus(
                        chargePoint,
                        OperationalChargePointStatus.OFFLINE,
                        " "
                ),
                StationErrorCode.CHARGE_POINT_STATUS_REASON_REQUIRED
        );
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void confirmedOrCheckedInBookingBlocksOffline() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        UUID chargePointId = UUID.randomUUID();
        when(chargePoint.getId()).thenReturn(chargePointId);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(bookingRepository.existsByConnectorChargePointIdAndStatusIn(
                eq(chargePointId),
                anyCollection()
        )).thenReturn(true);

        assertCode(
                () -> operationPolicy.requireCanChangeOperationalStatus(
                        chargePoint,
                        OperationalChargePointStatus.MAINTENANCE,
                        "Scheduled maintenance"
                ),
                StationErrorCode.CHARGE_POINT_HAS_ACTIVE_BOOKINGS
        );
    }

    private void assertCode(Runnable command, StationErrorCode expected) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected)
                );
    }

    @Test
    void reactivationRequiresReason() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        Station station = mock(Station.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.SUSPENDED);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);

        assertCode(
                () -> provisioningPolicy.requireCanReactivate(chargePoint, " "),
                StationErrorCode.CHARGE_POINT_STATUS_REASON_REQUIRED
        );
    }
}
