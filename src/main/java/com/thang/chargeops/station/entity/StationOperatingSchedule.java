package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.exception.violation.StationPricingViolation;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
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

    public static StationOperatingSchedule create(
            Station station,
            boolean open24Hours,
            Instant effectiveFrom
    ) {
        if (station == null) {
            throw new IllegalArgumentException("Operating schedule requires a station");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Operating schedule requires an effective time");
        }

        return StationOperatingSchedule.builder()
                .station(station)
                .open24Hours(open24Hours)
                .effectiveFrom(effectiveFrom)
                .build();
    }

    public void addPeriod(
            StationDayOfWeek day,
            LocalTime openTime,
            LocalTime closeTime,
            boolean enabled
    ) {
        if (open24Hours) {
            throw new StationPricingDomainException(
                    StationPricingViolation.OPEN_24_HOURS_PERIOD_NOT_ALLOWED,
                    "An open-24-hours schedule cannot contain periods"
            );
        }
        if (day == null || periods.stream().anyMatch(period -> period.getDayOfWeek() == day)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.OPERATING_WEEK_INVALID,
                    "Operating schedule requires one unique period per day"
            );
        }
        if (enabled && (openTime == null || closeTime == null)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.OPEN_DAY_TIME_REQUIRED,
                    "Enabled operating period requires open and close times"
            );
        }
        if (enabled && openTime.equals(closeTime)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.OPERATING_WINDOW_AMBIGUOUS,
                    "Enabled operating period requires an unambiguous time window"
            );
        }
        if (!enabled && (openTime != null || closeTime != null)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.CLOSED_DAY_TIME_PRESENT,
                    "Disabled operating period cannot contain times"
            );
        }

        addPeriod(StationOperatingPeriod.builder()
                .dayOfWeek(day)
                .openTime(enabled ? openTime : null)
                .closeTime(enabled ? closeTime : null)
                .enabled(enabled)
                .build());
    }

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

    public void expireAt(Instant at) {
        if (at == null || effectiveFrom == null || at.isBefore(effectiveFrom)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.CONFIGURATION_CONFLICT,
                    "Schedule expiry cannot precede its effective time"
            );
        }
        if (effectiveTo != null && !effectiveTo.equals(at)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.CONFIGURATION_CONFLICT,
                    "Operating schedule is already expired"
            );
        }
        effectiveTo = at;
    }
}
