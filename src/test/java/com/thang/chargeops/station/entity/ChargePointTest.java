package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.station.exception.ChargePointDomainException;
import com.thang.chargeops.station.exception.violation.ChargePointViolation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargePointTest {

    @Test
    void createsChargePointWithValidParameters() {
        Station station = new Station();
        ChargePoint cp = ChargePoint.create(
                station,
                "CP-01",
                "Trụ sạc nhanh số 1",
                "Khu vực A",
                new BigDecimal("120.00")
        );

        assertThat(cp.getStation()).isSameAs(station);
        assertThat(cp.getChargePointCode()).isEqualTo("CP-01");
        assertThat(cp.getName()).isEqualTo("Trụ sạc nhanh số 1");
        assertThat(cp.getZoneLabel()).isEqualTo("Khu vực A");
        assertThat(cp.getMaxPowerKw()).isEqualByComparingTo("120.00");
        assertThat(cp.getProvisioningStatus()).isEqualTo(ProvisioningStatus.PENDING_ACTIVATION);
        assertThat(cp.getOperationalChargePointStatus()).isEqualTo(OperationalChargePointStatus.AVAILABLE);
    }

    @Test
    void rejectsCreationWithNullStation() {
        assertThatThrownBy(() -> ChargePoint.create(
                null,
                "CP-01",
                "Name",
                "Zone",
                new BigDecimal("60.00")
        )).isInstanceOfSatisfying(
                ChargePointDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ChargePointViolation.STATION_REQUIRED)
        );
    }

    @Test
    void rejectsCreationWithBlankCode() {
        Station station = new Station();
        assertThatThrownBy(() -> ChargePoint.create(
                station,
                "   ",
                "Name",
                "Zone",
                new BigDecimal("60.00")
        )).isInstanceOfSatisfying(
                ChargePointDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ChargePointViolation.CODE_REQUIRED)
        );
    }

    @Test
    void rejectsMaxPowerBelowMinimum() {
        Station station = new Station();
        assertThatThrownBy(() -> ChargePoint.create(
                station,
                "CP-01",
                "Name",
                "Zone",
                new BigDecimal("2.50")
        )).isInstanceOfSatisfying(
                ChargePointDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ChargePointViolation.MAX_POWER_OUT_OF_RANGE)
        );
    }

    @Test
    void rejectsMaxPowerAboveMaximum() {
        Station station = new Station();
        assertThatThrownBy(() -> ChargePoint.create(
                station,
                "CP-01",
                "Name",
                "Zone",
                new BigDecimal("750.00")
        )).isInstanceOfSatisfying(
                ChargePointDomainException.class,
                e -> assertThat(e.getViolation()).isEqualTo(ChargePointViolation.MAX_POWER_OUT_OF_RANGE)
        );
    }

    @Test
    void updatesDisplayInfoAndTrimsWhitespace() {
        Station station = new Station();
        ChargePoint cp = ChargePoint.create(station, "CP-01", "Old Name", "Old Zone", new BigDecimal("60.00"));

        cp.updateDisplayInfo("  New Name  ", "  New Zone  ");

        assertThat(cp.getName()).isEqualTo("New Name");
        assertThat(cp.getZoneLabel()).isEqualTo("New Zone");
    }

    @Test
    void addsConnectorWithinCapacity() {
        Station station = new Station();
        ChargePoint cp = ChargePoint.create(station, "CP-01", "Name", "Zone", new BigDecimal("120.00"));

        Connector connector = Connector.create(
                cp,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        );
        cp.addConnector(connector);

        assertThat(cp.getConnectors()).containsExactly(connector);
        assertThat(connector.getChargePoint()).isSameAs(cp);
    }

    @Test
    void rejectsAddingConnectorExceedingMaxPower() {
        Station station = new Station();
        ChargePoint cp = ChargePoint.create(station, "CP-01", "Name", "Zone", new BigDecimal("60.00"));

        Connector connector = Connector.create(
                null,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        );

        assertThatThrownBy(() -> cp.addConnector(connector))
                .isInstanceOfSatisfying(
                        ChargePointDomainException.class,
                        e -> assertThat(e.getViolation()).isEqualTo(ChargePointViolation.CONNECTOR_POWER_EXCEEDS_MAX_POWER)
                );
    }

    @Test
    void rejectsLoweringMaxPowerBelowExistingConnectorPower() {
        Station station = new Station();
        ChargePoint cp = ChargePoint.create(station, "CP-01", "Name", "Zone", new BigDecimal("120.00"));
        Connector connector = Connector.create(
                cp,
                "C01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        );
        cp.addConnector(connector);

        assertThatThrownBy(() -> cp.updateMaxPowerKw(new BigDecimal("60.00")))
                .isInstanceOfSatisfying(
                        ChargePointDomainException.class,
                        e -> assertThat(e.getViolation()).isEqualTo(ChargePointViolation.CONNECTOR_POWER_EXCEEDS_MAX_POWER)
                );
    }

    @Test
    void adminCanReactivateSuspendedChargePoint() {
        ChargePoint chargePoint = ChargePoint.create(
                new Station(),
                "CP-01",
                "Name",
                "Zone",
                new BigDecimal("60.00")
        );
        chargePoint.activate();
        chargePoint.suspend();

        chargePoint.reactivate();

        assertThat(chargePoint.getProvisioningStatus()).isEqualTo(ProvisioningStatus.ACTIVE);
    }
}
