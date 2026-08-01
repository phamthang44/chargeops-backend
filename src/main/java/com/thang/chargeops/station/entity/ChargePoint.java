package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE charge_points SET deleted_at = now() WHERE id = ?")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "charge_points")
public class ChargePoint extends SoftDeletableEntity {

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

}
