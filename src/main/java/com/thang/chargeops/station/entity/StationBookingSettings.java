package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "station_booking_settings")
public class StationBookingSettings extends AuditableEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false, unique = true)
    private Station station;

    @Column(name = "min_duration_minutes", nullable = false)
    @Builder.Default
    private int minDurationMinutes = 30;

    @Column(name = "duration_step_minutes", nullable = false)
    @Builder.Default
    private int durationStepMinutes = 30;

    @Column(name = "max_duration_minutes", nullable = false)
    @Builder.Default
    private int maxDurationMinutes = 180;

    @Column(name = "base_price_vnd", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal basePriceVnd = new BigDecimal("3400.00");

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
