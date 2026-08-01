package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.profile.UserProfile;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "licenses")
public class License extends AuditableEntity {

    @JoinColumn(name = "station_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Station station;

    @JoinColumn(name = "owner_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserProfile owner;

    @Column(name = "plan", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Plan plan;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private LicenseStatus status;

    public void setExpiresAt(Instant startAt) {
        if (startAt == null) throw new IllegalArgumentException("License start time cannot be null");
        if (this.plan.equals(Plan.MONTHLY)) {
            this.expiresAt = startAt.plus(30, ChronoUnit.DAYS);
        } else {
            this.expiresAt = startAt.plus(365, ChronoUnit.DAYS);
        }
    }

}
