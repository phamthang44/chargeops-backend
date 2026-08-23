package com.thang.chargeops.station.policy;

import com.thang.chargeops.booking.BookingRepository;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.impl.CheckInChallengePolicyImpl;
import com.thang.chargeops.station.policy.impl.ConnectorOperationPolicyImpl;
import com.thang.chargeops.station.policy.impl.ConnectorProvisioningPolicyImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectorLifeCyclePolicyImplTest {

    @Mock
    private BookingRepository bookingRepository;


    private ConnectorProvisioningPolicyImpl provisioningPolicy;
    private ConnectorOperationPolicyImpl operationPolicy;
    private CheckInChallengePolicyImpl challengePolicy;

    @BeforeEach
    void setUp() {
        provisioningPolicy = new ConnectorProvisioningPolicyImpl();
        operationPolicy = new ConnectorOperationPolicyImpl(bookingRepository);
        challengePolicy = new CheckInChallengePolicyImpl();
    }

    @Test
    void connectorHardwareLocksAfterChargePointActivation() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);

        assertCode(
                () -> provisioningPolicy.requireCanProvision(chargePoint),
                StationErrorCode.CONNECTOR_HARDWARE_LOCKED
        );
    }

    @Test
    void connectorHardwareUpdateLocksAfterChargePointActivation() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);

        assertCode(
                () -> provisioningPolicy.requireCanUpdate(chargePoint),
                StationErrorCode.CONNECTOR_HARDWARE_LOCKED
        );
    }

    @Test
    void connectorHardwareUpdateAllowedWhenPendingActivationAndStationActive() {
        Station station = mock(Station.class);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.PENDING_ACTIVATION);
        when(chargePoint.getStation()).thenReturn(station);

        provisioningPolicy.requireCanUpdate(chargePoint);
    }

    @Test
    void ownerCannotSetSystemManagedInUseStatus() {
        Connector connector = activeConnector(RuntimeStatus.AVAILABLE);

        assertCode(
                () -> operationPolicy.requireCanChangeRuntimeStatus(connector, RuntimeStatus.IN_USE, null),
                StationErrorCode.CONNECTOR_RUNTIME_STATUS_SYSTEM_MANAGED
        );
    }

    @Test
    void activeBookingBlocksTakingConnectorOffline() {
        Connector connector = activeConnector(RuntimeStatus.AVAILABLE);
        UUID connectorId = UUID.randomUUID();
        when(connector.getId()).thenReturn(connectorId);
        when(bookingRepository.existsByConnectorIdAndStatusIn(
                eq(connectorId),
                anyCollection()
        )).thenReturn(true);

        assertCode(
                () -> operationPolicy.requireCanChangeRuntimeStatus(
                        connector,
                        RuntimeStatus.OFFLINE,
                        "Repair"
                ),
                StationErrorCode.CONNECTOR_HAS_ACTIVE_BOOKINGS
        );
    }

    @Test
    void checkInChallengeRequiresAvailableEquipmentState() {
        Station station = mock(Station.class);
        ChargePoint chargePoint = mock(ChargePoint.class);
        Connector connector = mock(Connector.class);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.AVAILABLE);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.AVAILABLE);

        challengePolicy.requireCanIssue(connector, Instant.now());

        verifyNoInteractions(bookingRepository);
    }

    private Connector activeConnector(RuntimeStatus runtimeStatus) {
        ChargePoint chargePoint = mock(ChargePoint.class);
        Connector connector = mock(Connector.class);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        lenient().when(connector.getRuntimeStatus()).thenReturn(runtimeStatus);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        return connector;
    }

    private void assertCode(Runnable command, StationErrorCode expected) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected)
                );
    }
}
