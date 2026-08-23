package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.station.exception.ConnectorDomainException;
import com.thang.chargeops.station.exception.violation.ConnectorViolation;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE connectors SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Entity
@Table(name = "connectors", indexes = {
        @Index(name = "idx_connectors_charge_point_id", columnList = "charge_point_id")
})
public class Connector extends SoftDeletableEntity {

    public static final BigDecimal MIN_POWER_KW = new BigDecimal("3.00");
    public static final BigDecimal MAX_POWER_KW = new BigDecimal("360.00");

    @JoinColumn(name = "charge_point_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private ChargePoint chargePoint;

    @Column(name = "connector_code", nullable = false, length = 50)
    private String connectorCode;


    @Enumerated(EnumType.STRING)
    @Column(name = "connector_type", nullable = false, length = 30)
    private ConnectorType connectorType;

    @Column(name = "power_kw", nullable = false, precision = 8, scale = 2)
    private BigDecimal powerKw;

    @Enumerated(EnumType.STRING)
    @Column(name = "charger_type", nullable = false, length = 10)
    private ChargerType chargerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime_status", nullable = false, length = 30)
    private RuntimeStatus runtimeStatus;

    @Version
    @Column(nullable = false)
    private long version;

    public static Connector create(
            ChargePoint chargePoint,
            String connectorCode,
            ConnectorType connectorType,
            BigDecimal powerKw,
            ChargerType chargerType
    ) {
        if (connectorCode == null || connectorCode.isBlank()) {
            throw new ConnectorDomainException(
                    ConnectorViolation.CODE_REQUIRED,
                    "Connector code is required"
            );
        }
        if (connectorType == null) {
            throw new ConnectorDomainException(
                    ConnectorViolation.TYPE_REQUIRED,
                    "Connector type is required"
            );
        }
        if (chargerType == null) {
            throw new ConnectorDomainException(
                    ConnectorViolation.TYPE_REQUIRED,
                    "Charger type is required"
            );
        }
        validatePowerRange(powerKw);
        validateTypeCompatibility(connectorType, chargerType);

        if (chargePoint != null && chargePoint.getMaxPowerKw() != null) {
            if (powerKw.compareTo(chargePoint.getMaxPowerKw()) > 0) {
                throw new ConnectorDomainException(
                        ConnectorViolation.POWER_EXCEEDS_MAX_POWER,
                        "Connector power (" + powerKw + " kW) cannot exceed charge point max power (" + chargePoint.getMaxPowerKw() + " kW)"
                );
            }
        }

        Connector connector = new Connector();
        connector.chargePoint = chargePoint;
        connector.connectorCode = connectorCode.trim();
        connector.connectorType = connectorType;
        connector.powerKw = powerKw;
        connector.chargerType = chargerType;
        connector.runtimeStatus = RuntimeStatus.AVAILABLE;
        return connector;
    }

    public void updateRuntimeStatus(RuntimeStatus newStatus) {
        if (newStatus == null) {
            throw new ConnectorDomainException(
                    ConnectorViolation.STATUS_REQUIRED,
                    "Runtime status cannot be null"
            );
        }
        this.runtimeStatus = newStatus;
    }

    public void updateHardware(
            ConnectorType newConnectorType,
            BigDecimal newPowerKw,
            ChargerType newChargerType
    ) {
        if (newConnectorType != null) {
            this.connectorType = newConnectorType;
        }
        if (newChargerType != null) {
            this.chargerType = newChargerType;
        }
        if (this.connectorType != null && this.chargerType != null) {
            validateTypeCompatibility(this.connectorType, this.chargerType);
        }
        if (newPowerKw != null) {
            validatePowerRange(newPowerKw);
            if (chargePoint != null && chargePoint.getMaxPowerKw() != null) {
                if (newPowerKw.compareTo(chargePoint.getMaxPowerKw()) > 0) {
                    throw new ConnectorDomainException(
                            ConnectorViolation.POWER_EXCEEDS_MAX_POWER,
                            "Connector power (" + newPowerKw + " kW) cannot exceed charge point max power (" + chargePoint.getMaxPowerKw() + " kW)"
                    );
                }
            }
            this.powerKw = newPowerKw;
        }
    }

    void setChargePointInternal(ChargePoint chargePoint) {
        this.chargePoint = chargePoint;
    }

    private static void validatePowerRange(BigDecimal powerKw) {
        if (powerKw == null || powerKw.compareTo(MIN_POWER_KW) < 0 || powerKw.compareTo(MAX_POWER_KW) > 0) {
            throw new ConnectorDomainException(
                    ConnectorViolation.POWER_OUT_OF_RANGE,
                    "Power must be between " + MIN_POWER_KW + " kW and " + MAX_POWER_KW + " kW"
            );
        }
    }

    private static void validateTypeCompatibility(ConnectorType connectorType, ChargerType chargerType) {
        boolean isAc = connectorType == ConnectorType.TYPE2;
        boolean isDc = connectorType == ConnectorType.CCS2
                || connectorType == ConnectorType.CHADEMO
                || connectorType == ConnectorType.GBT;
        if (chargerType == ChargerType.AC && !isAc) {
            throw new ConnectorDomainException(
                    ConnectorViolation.TYPE_MISMATCH,
                    "Connector type " + connectorType + " is incompatible with charger type " + chargerType
            );
        }
        if (chargerType == ChargerType.DC && !isDc) {
            throw new ConnectorDomainException(
                    ConnectorViolation.TYPE_MISMATCH,
                    "Connector type " + connectorType + " is incompatible with charger type " + chargerType
            );
        }
    }
}
