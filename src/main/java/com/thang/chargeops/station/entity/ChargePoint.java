package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.station.exception.ChargePointDomainException;
import com.thang.chargeops.station.exception.violation.ChargePointViolation;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE charge_points SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Entity
@Table(name = "charge_points", indexes = {
        @Index(name = "idx_charge_points_station_id", columnList = "station_id")
})
public class ChargePoint extends SoftDeletableEntity {

    public static final BigDecimal MIN_MAX_POWER_KW = new BigDecimal("3.00");
    public static final BigDecimal MAX_MAX_POWER_KW = new BigDecimal("720.00");

    @JoinColumn(name = "station_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Station station;

    @Column(name = "charge_point_code", nullable = false, length = 80)
    private String chargePointCode;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "zone_label", length = 100)
    private String zoneLabel;

    @Column(name = "max_power_kw", precision = 8, scale = 2)
    private BigDecimal maxPowerKw;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_status", nullable = false, length = 30)
    private ProvisioningStatus provisioningStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 30)
    private OperationalChargePointStatus operationalChargePointStatus;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "chargePoint")
    @OrderBy("connectorCode ASC")
    private List<Connector> connectors = new ArrayList<>();

    public static ChargePoint create(
            Station station,
            String chargePointCode,
            String name,
            String zoneLabel,
            BigDecimal maxPowerKw
    ) {
        if (station == null) {
            throw new ChargePointDomainException(
                    ChargePointViolation.STATION_REQUIRED,
                    "Station reference is required"
            );
        }
        if (chargePointCode == null || chargePointCode.isBlank()) {
            throw new ChargePointDomainException(
                    ChargePointViolation.CODE_REQUIRED,
                    "Charge point code is required"
            );
        }
        validateMaxPower(maxPowerKw);

        ChargePoint cp = new ChargePoint();
        cp.station = station;
        cp.chargePointCode = chargePointCode.trim();
        cp.name = name != null ? name.trim() : null;
        cp.zoneLabel = zoneLabel != null ? zoneLabel.trim() : null;
        cp.maxPowerKw = maxPowerKw;
        cp.provisioningStatus = ProvisioningStatus.PENDING_ACTIVATION;
        cp.operationalChargePointStatus = OperationalChargePointStatus.AVAILABLE;
        return cp;
    }

    public void updateDisplayInfo(String name, String zoneLabel) {
        this.name = name != null ? name.trim() : null;
        this.zoneLabel = zoneLabel != null ? zoneLabel.trim() : null;
    }

    public void updateMaxPowerKw(BigDecimal newMaxPowerKw) {
        validateMaxPower(newMaxPowerKw);
        if (newMaxPowerKw != null && !connectors.isEmpty()) {
            for (Connector conn : connectors) {
                if (conn.getPowerKw() != null && conn.getPowerKw().compareTo(newMaxPowerKw) > 0) {
                    throw new ChargePointDomainException(
                            ChargePointViolation.CONNECTOR_POWER_EXCEEDS_MAX_POWER,
                            "Connector power (" + conn.getPowerKw() + " kW) cannot exceed new max power (" + newMaxPowerKw + " kW)"
                    );
                }
            }
        }
        this.maxPowerKw = newMaxPowerKw;
    }

    public void updateOperationalStatus(OperationalChargePointStatus newStatus) {
        if (newStatus == null) {
            throw new ChargePointDomainException(
                    ChargePointViolation.STATUS_REQUIRED,
                    "Operational status cannot be null"
            );
        }
        this.operationalChargePointStatus = newStatus;
    }

    public void activate() {
        if (provisioningStatus != ProvisioningStatus.PENDING_ACTIVATION) {
            throw new ChargePointDomainException(
                    ChargePointViolation.INVALID_PROVISIONING_TRANSITION,
                    "Only a pending-activation charge point can be activated"
            );
        }
        this.provisioningStatus = ProvisioningStatus.ACTIVE;
    }

    public void suspend() {
        if (provisioningStatus != ProvisioningStatus.ACTIVE) {
            throw new ChargePointDomainException(
                    ChargePointViolation.INVALID_PROVISIONING_TRANSITION,
                    "Only an active charge point can be suspended"
            );
        }
        this.provisioningStatus = ProvisioningStatus.SUSPENDED;
    }

    public void reactivate() {
        if (provisioningStatus != ProvisioningStatus.SUSPENDED) {
            throw new ChargePointDomainException(
                    ChargePointViolation.INVALID_PROVISIONING_TRANSITION,
                    "Only a suspended charge point can be reactivated"
            );
        }
        this.provisioningStatus = ProvisioningStatus.ACTIVE;
    }

    public void addConnector(Connector connector) {
        if (connector == null) {
            return;
        }
        if (this.maxPowerKw != null && connector.getPowerKw() != null
                && connector.getPowerKw().compareTo(this.maxPowerKw) > 0) {
            throw new ChargePointDomainException(
                    ChargePointViolation.CONNECTOR_POWER_EXCEEDS_MAX_POWER,
                    "Connector power (" + connector.getPowerKw() + " kW) cannot exceed charge point max power (" + this.maxPowerKw + " kW)"
            );
        }
        this.connectors.add(connector);
        connector.setChargePointInternal(this);
    }

    public void removeConnector(Connector connector) {
        if (connector != null) {
            this.connectors.remove(connector);
            connector.setChargePointInternal(null);
        }
    }

    public List<Connector> getConnectors() {
        return Collections.unmodifiableList(this.connectors);
    }

    public boolean hasConnectors() {
        return !connectors.isEmpty();
    }

    private static void validateMaxPower(BigDecimal maxPowerKw) {
        if (maxPowerKw != null && (maxPowerKw.compareTo(MIN_MAX_POWER_KW) < 0 || maxPowerKw.compareTo(MAX_MAX_POWER_KW) > 0)) {
                throw new ChargePointDomainException(
                        ChargePointViolation.MAX_POWER_OUT_OF_RANGE,
                        "Max power must be between " + MIN_MAX_POWER_KW + " kW and " + MAX_MAX_POWER_KW + " kW"
                );
            }

    }
}
