package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

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
        @Index(name = "idx_station_operating_periods_schedule_day", columnList = "schedule_id, day_of_week")
})
public class StationOperatingPeriod extends SoftDeletableEntity {

    @JoinColumn(name = "schedule_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private StationOperatingSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 20)
    private StationDayOfWeek dayOfWeek;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
