package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "tou_rates", indexes = {
        @Index(name = "idx_tou_rates_station_day", columnList = "station_id, day_type, effective_from"),
        @Index(name = "idx_tou_rates_window", columnList = "station_id, day_type, start_time, end_time")
})
public class TouRate extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_code", nullable = false, length = 20)
    private TouRatePeriodCode periodCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private TouRateDayType dayType;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "price_per_kwh", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricePerKwh;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    public boolean isActive(Instant now) {
        if (effectiveFrom == null || effectiveFrom.isAfter(now)) {
            return false;
        }
        return effectiveTo == null || effectiveTo.isAfter(now);
    }
}
