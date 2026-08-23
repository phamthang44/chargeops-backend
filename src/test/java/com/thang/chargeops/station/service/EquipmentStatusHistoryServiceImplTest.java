package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.ChargePointStatusEvent;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import com.thang.chargeops.station.repository.ChargePointStatusEventRepository;
import com.thang.chargeops.station.repository.ConnectorStatusEventRepository;
import com.thang.chargeops.station.service.impl.EquipmentStatusHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EquipmentStatusHistoryServiceImplTest {

    @Mock
    private ChargePointStatusEventRepository chargePointStatusEventRepository;

    @Mock
    private ConnectorStatusEventRepository connectorStatusEventRepository;

    private EquipmentStatusHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EquipmentStatusHistoryServiceImpl(
                chargePointStatusEventRepository,
                connectorStatusEventRepository
        );
    }

    @Test
    void recordsTrimmedReasonForChargePointOperationalTransition() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        UserProfile performedBy = mock(UserProfile.class);

        service.recordChargePointOperationalTransition(
                chargePoint,
                OperationalChargePointStatus.AVAILABLE,
                OperationalChargePointStatus.MAINTENANCE,
                EquipmentStatusActorType.OWNER,
                performedBy,
                "  Scheduled maintenance  "
        );

        ArgumentCaptor<ChargePointStatusEvent> captor =
                ArgumentCaptor.forClass(ChargePointStatusEvent.class);
        verify(chargePointStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo("AVAILABLE");
        assertThat(captor.getValue().getToStatus()).isEqualTo("MAINTENANCE");
        assertThat(captor.getValue().getReason()).isEqualTo("Scheduled maintenance");
        assertThat(captor.getValue().getPerformedAt()).isNotNull();
    }

    @Test
    void recordsSystemManagedInUseTransitionWithoutUserProfile() {
        Connector connector = mock(Connector.class);

        service.recordConnectorSystemRuntimeTransition(
                connector,
                RuntimeStatus.AVAILABLE,
                RuntimeStatus.IN_USE,
                "Booking session started"
        );

        ArgumentCaptor<ConnectorStatusEvent> captor =
                ArgumentCaptor.forClass(ConnectorStatusEvent.class);
        verify(connectorStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(EquipmentStatusActorType.SYSTEM);
        assertThat(captor.getValue().getPerformedBy()).isNull();
        assertThat(captor.getValue().getReason()).isEqualTo("Booking session started");
    }

    @Test
    void recordsTrimmedReasonForConnectorRuntimeTransition() {
        Connector connector = mock(Connector.class);
        UserProfile performedBy = mock(UserProfile.class);

        service.recordConnectorRuntimeTransition(
                connector,
                RuntimeStatus.AVAILABLE,
                RuntimeStatus.OFFLINE,
                EquipmentStatusActorType.OWNER,
                performedBy,
                "  Cable replacement  "
        );

        ArgumentCaptor<ConnectorStatusEvent> captor =
                ArgumentCaptor.forClass(ConnectorStatusEvent.class);
        verify(connectorStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(RuntimeStatus.AVAILABLE);
        assertThat(captor.getValue().getToStatus()).isEqualTo(RuntimeStatus.OFFLINE);
        assertThat(captor.getValue().getReason()).isEqualTo("Cable replacement");
        assertThat(captor.getValue().getPerformedAt()).isNotNull();
    }
}
