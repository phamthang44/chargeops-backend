package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.exception.violation.StationPricingViolation;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

@Getter
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

    public static TouRate create(
            Station station,
            String name,
            TouRatePeriodCode periodCode,
            TouRateDayType dayType,
            LocalTime startTime,
            LocalTime endTime,
            BigDecimal pricePerKwh,
            Instant effectiveFrom
    ) {
        if (station == null || periodCode == null || dayType == null || effectiveFrom == null) {
            throw new IllegalArgumentException("TOU rate is missing required pricing data");
        }
        if (name == null || name.isBlank()) {
            throw new StationPricingDomainException(
                    StationPricingViolation.TOU_NAME_REQUIRED,
                    "TOU rate name cannot be blank"
            );
        }
        if (startTime == null || endTime == null || startTime.equals(endTime)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.TOU_WINDOW_INVALID,
                    "TOU rate requires an unambiguous time window"
            );
        }
        if (pricePerKwh == null || pricePerKwh.signum() <= 0) {
            throw new StationPricingDomainException(
                    StationPricingViolation.TOU_RATE_INVALID,
                    "TOU price must be positive"
            );
        }

        return TouRate.builder()
                .station(station)
                .name(name.trim())
                .periodCode(periodCode)
                .dayType(dayType)
                .startTime(startTime)
                .endTime(endTime)
                .pricePerKwh(pricePerKwh)
                .effectiveFrom(effectiveFrom)
                .build();
    }

    public boolean isActive(Instant now) {
        if (effectiveFrom == null || effectiveFrom.isAfter(now)) {
            return false;
        }
        return effectiveTo == null || effectiveTo.isAfter(now);
    }

    public void expireAt(Instant at) {
        if (at == null || effectiveFrom == null || at.isBefore(effectiveFrom)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.CONFIGURATION_CONFLICT,
                    "TOU rate expiry cannot precede its effective time"
            );
        }
        if (effectiveTo != null && !effectiveTo.equals(at)) {
            throw new StationPricingDomainException(
                    StationPricingViolation.CONFIGURATION_CONFLICT,
                    "TOU rate is already expired"
            );
        }
        effectiveTo = at;
    }

    public boolean appliesAt(DayOfWeek currentDay, LocalTime currentTime) {
        if (currentDay == null || currentTime == null) {
            return false;
        }
        if (startTime.isBefore(endTime)) {
            return appliesOn(currentDay)
                    && !currentTime.isBefore(startTime)
                    && currentTime.isBefore(endTime);
        }
        if (!currentTime.isBefore(startTime)) {
            return appliesOn(currentDay);
        }
        return currentTime.isBefore(endTime) && appliesOn(currentDay.minus(1));
    }

    private boolean appliesOn(DayOfWeek day) {
        return switch (dayType) {
            case DAILY -> true;
            case WEEKDAY -> day.getValue() <= DayOfWeek.FRIDAY.getValue();
            case WEEKEND -> day.equals(DayOfWeek.SATURDAY) || day.equals(DayOfWeek.SUNDAY);
        };
    }
}
