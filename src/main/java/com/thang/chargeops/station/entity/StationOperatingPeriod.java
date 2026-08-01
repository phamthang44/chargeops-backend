package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalTime;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE station_operating_periods SET deleted_at = now() WHERE id = ?")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "station_operating_periods", indexes = {
        @Index(name = "idx_station_operating_periods_station_id", columnList = "station_id"),
        @Index(name = "idx_station_operating_periods_station_day", columnList = "station_id, day_of_week, effective_from")
})
public class StationOperatingPeriod extends SoftDeletableEntity {

    @JoinColumn(name = "station_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 20)
    private StationDayOfWeek dayOfWeek;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;
}
