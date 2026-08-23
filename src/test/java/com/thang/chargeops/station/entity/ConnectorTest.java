package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.station.exception.ConnectorDomainException;
import com.thang.chargeops.station.exception.violation.ConnectorViolation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorTest {

    @Test
    void createsAcConnectorSuccessfully() {
        Connector connector = Connector.create(
                null,
                "AC-01",
                ConnectorType.TYPE2,
                new BigDecimal("22.00"),
                ChargerType.AC
        );

        assertThat(connector.getConnectorCode()).isEqualTo("AC-01");
        assertThat(connector.getConnectorType()).isEqualTo(ConnectorType.TYPE2);
        assertThat(connector.getChargerType()).isEqualTo(ChargerType.AC);
        assertThat(connector.getPowerKw()).isEqualByComparingTo("22.00");
        assertThat(connector.getRuntimeStatus()).isEqualTo(RuntimeStatus.AVAILABLE);
    }

    @Test
    void createsDcConnectorSuccessfully() {
        Connector connector = Connector.create(
                null,
                "DC-01",
                ConnectorType.CCS2,
                new BigDecimal("180.00"),
                ChargerType.DC
        );

        assertThat(connector.getConnectorCode()).isEqualTo("DC-01");
        assertThat(connector.getConnectorType()).isEqualTo(ConnectorType.CCS2);
        assertThat(connector.getChargerType()).isEqualTo(ChargerType.DC);
        assertThat(connector.getPowerKw()).isEqualByComparingTo("180.00");
    }

    @Test
    void rejectsBlankConnectorCode() {
        assertThatThrownBy(() -> Connector.create(
                null,
                "   ",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        )).isInstanceOfSatisfying(
                ConnectorDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ConnectorViolation.CODE_REQUIRED)
        );
    }

    @Test
    void rejectsPowerBelowMinimum() {
        assertThatThrownBy(() -> Connector.create(
                null,
                "C01",
                ConnectorType.TYPE2,
                new BigDecimal("2.00"),
                ChargerType.AC
        )).isInstanceOfSatisfying(
                ConnectorDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ConnectorViolation.POWER_OUT_OF_RANGE)
        );
    }

    @Test
    void rejectsPowerAboveMaximum() {
        assertThatThrownBy(() -> Connector.create(
                null,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("400.00"),
                ChargerType.DC
        )).isInstanceOfSatisfying(
                ConnectorDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ConnectorViolation.POWER_OUT_OF_RANGE)
        );
    }

    @Test
    void rejectsTypeMismatchWhenAcWithCcs2() {
        assertThatThrownBy(() -> Connector.create(
                null,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("50.00"),
                ChargerType.AC
        )).isInstanceOfSatisfying(
                ConnectorDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ConnectorViolation.TYPE_MISMATCH)
        );
    }

    @Test
    void rejectsTypeMismatchWhenDcWithType2() {
        assertThatThrownBy(() -> Connector.create(
                null,
                "C01",
                ConnectorType.TYPE2,
                new BigDecimal("22.00"),
                ChargerType.DC
        )).isInstanceOfSatisfying(
                ConnectorDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ConnectorViolation.TYPE_MISMATCH)
        );
    }

    @Test
    void rejectsCreationWhenPowerExceedsChargePointCapacity() {
        ChargePoint cp = ChargePoint.create(new Station(), "CP-01", "Name", "Zone", new BigDecimal("60.00"));

        assertThatThrownBy(() -> Connector.create(
                cp,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        )).isInstanceOfSatisfying(
                ConnectorDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ConnectorViolation.POWER_EXCEEDS_MAX_POWER)
        );
    }

    @Test
    void updatesRuntimeStatus() {
        Connector connector = Connector.create(
                null,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        );

        connector.updateRuntimeStatus(RuntimeStatus.IN_USE);
        assertThat(connector.getRuntimeStatus()).isEqualTo(RuntimeStatus.IN_USE);

        connector.updateRuntimeStatus(RuntimeStatus.OFFLINE);
        assertThat(connector.getRuntimeStatus()).isEqualTo(RuntimeStatus.OFFLINE);
    }
}
