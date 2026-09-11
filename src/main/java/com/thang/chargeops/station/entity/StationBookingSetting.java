package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.exception.violation.StationPricingViolation;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Entity
@Table(name = "station_booking_settings")
public class StationBookingSetting extends AuditableEntity {

    public static final int DEFAULT_MIN_DURATION_MINUTES = 30;
    public static final int DEFAULT_DURATION_STEP_MINUTES = 30;
    public static final int DEFAULT_MAX_DURATION_MINUTES = 180;
    public static final BigDecimal DEFAULT_BASE_PRICE_VND = new BigDecimal("3400.00");

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false, unique = true)
    private Station station;

    @Column(name = "min_duration_minutes", nullable = false)
    @Builder.Default
    private int minDurationMinutes = DEFAULT_MIN_DURATION_MINUTES;

    @Column(name = "duration_step_minutes", nullable = false)
    @Builder.Default
    private int durationStepMinutes = DEFAULT_DURATION_STEP_MINUTES;

    @Column(name = "max_duration_minutes", nullable = false)
    @Builder.Default
    private int maxDurationMinutes = DEFAULT_MAX_DURATION_MINUTES;

    @Column(name = "base_price_vnd", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal basePriceVnd = DEFAULT_BASE_PRICE_VND;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static StationBookingSetting createDefault(Station station) {
        if (station == null) {
            throw new IllegalArgumentException("Station booking settings require a station");
        }
        return StationBookingSetting.builder()
                .station(station)
                .minDurationMinutes(DEFAULT_MIN_DURATION_MINUTES)
                .durationStepMinutes(DEFAULT_DURATION_STEP_MINUTES)
                .maxDurationMinutes(DEFAULT_MAX_DURATION_MINUTES)
                .basePriceVnd(DEFAULT_BASE_PRICE_VND)
                .build();
    }

    public void updatePricing(int minDurationMinutes, BigDecimal basePriceVnd) {
        requireValidMinDuration(minDurationMinutes);
        requirePositivePrice(basePriceVnd);

        this.minDurationMinutes = minDurationMinutes;
        this.durationStepMinutes = DEFAULT_DURATION_STEP_MINUTES;
        this.maxDurationMinutes = DEFAULT_MAX_DURATION_MINUTES;
        this.basePriceVnd = basePriceVnd;
    }

    private static void requireValidMinDuration(int minDurationMinutes) {
        if (minDurationMinutes == 90) {
            throw new StationPricingDomainException(
                    StationPricingViolation.MIN_BOOKING_DURATION_90_NOT_SUPPORTED,
                    "Minimum booking duration of 90 minutes is not supported"
            );
        }
        if (minDurationMinutes != 30 && minDurationMinutes != 60) {
            throw new StationPricingDomainException(
                    StationPricingViolation.MIN_BOOKING_DURATION_INVALID,
                    "Minimum booking duration must be 30, 60, or 90 minutes"
            );
        }
    }

    private static void requirePositivePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new StationPricingDomainException(
                    StationPricingViolation.BASE_PRICE_INVALID,
                    "Base price must be positive"
            );
        }
    }

}
