package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "station_operating_schedules", indexes = {
        @Index(name = "idx_station_operating_schedules_station", columnList = "station_id, effective_from")
})
public class StationOperatingSchedule extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "open_24_hours", nullable = false)
    private boolean open24Hours;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Builder.Default
    @OneToMany(mappedBy = "schedule", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, orphanRemoval = true)
    @OrderBy("dayOfWeek ASC, openTime ASC")
    private List<StationOperatingPeriod> periods = new ArrayList<>();

    public void addPeriod(StationOperatingPeriod period) {
        periods.add(period);
        period.setSchedule(this);
    }

    public void removePeriod(StationOperatingPeriod period) {
        periods.remove(period);
        period.setSchedule(null);
    }

    public boolean isFuture(Instant now) {
        return effectiveFrom != null && effectiveFrom.isAfter(now);
    }

    public boolean isActive(Instant now) {
        if (effectiveFrom == null || effectiveFrom.isAfter(now)) {
            return false;
        }
        return effectiveTo == null || effectiveTo.isAfter(now);
    }

    public boolean isHistorical(Instant now) {
        return effectiveTo != null && !effectiveTo.isAfter(now);
    }
}
