package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE connectors SET deleted_at = now() WHERE id = ?")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "connectors", indexes = {
        @Index(name = "idx_connectors_charge_point_id", columnList = "charge_point_id")
})
public class Connector extends SoftDeletableEntity {

    @JoinColumn(name = "charge_point_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private ChargePoint chargePoint;

    @Column(name = "connector_code", nullable = false, length = 50)
    private String connectorCode;

    @Column(name = "qr_token", nullable = false, unique = true)
    private UUID qrToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "connector_type", nullable = false, length = 30)
    private ConnectorType connectorType;

    @Column(name = "power_kw", nullable = false, precision = 8, scale = 2)
    private BigDecimal powerKw;

    @Enumerated(EnumType.STRING)
    @Column(name = "charger_type", nullable = false, length = 10)
    private ChargerType chargerType;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime_status", nullable = false, length = 30)
    private RuntimeStatus runtimeStatus;
}
