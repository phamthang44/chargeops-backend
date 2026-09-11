package com.thang.chargeops.booking.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Objects;

@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@Entity
@Table(name = "booking_price_lines")
public class BookingPriceLine extends AuditableEntity {

    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Booking booking;

    @Column(name = "sequence", nullable = false, updatable = false)
    private Integer sequence;

    @Column(name = "segment_start", nullable = false, updatable = false)
    private Instant segmentStart;

    @Column(name = "segment_end", nullable = false, updatable = false)
    private Instant segmentEnd;

    @Column(name = "duration_minutes", nullable = false, updatable = false)
    private Integer durationMinutes;

    @Column(name = "label", nullable = false, updatable = false, length = 100)
    private String label;

    @Column(name = "period_code", nullable = false, updatable = false, length = 30)
    private String periodCode;

    @Column(name = "rate_vnd_per_kwh", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal rateVndPerKwh;

    @Column(name = "estimated_energy_kwh", nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal estimatedEnergyKwh;

    @Column(name = "power_kw", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal powerKw;

    @Column(name = "energy_factor", nullable = false, updatable = false, precision = 8, scale = 6)
    private BigDecimal energyFactor;

    @Column(name = "formula_version", nullable = false, updatable = false, length = 50)
    private String formulaVersion;

    @Column(name = "amount", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    void attachTo(Booking booking) {
        Objects.requireNonNull(booking, "booking must not be null");
        if (this.booking != null && this.booking != booking) {
            throw new IllegalStateException("Price line is already attached to another booking");
        }
        this.booking = booking;
    }
}
