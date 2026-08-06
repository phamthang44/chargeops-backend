package com.thang.chargeops.booking;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_driver_id", columnList = "driver_id"),
        @Index(name = "idx_bookings_connector_id", columnList = "connector_id"),
        @Index(name = "idx_bookings_status", columnList = "status"),
        @Index(name = "idx_bookings_connector_range", columnList = "connector_id, start_at, end_at")
})
public class Booking extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private UserProfile driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false)
    private Connector connector;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "station_name_snapshot", nullable = false)
    private String stationNameSnapshot;

    @Column(name = "station_address_snapshot", nullable = false)
    private String stationAddressSnapshot;

    @Column(name = "charge_point_code_snapshot", nullable = false)
    private String chargePointCodeSnapshot;

    @Column(name = "connector_code_snapshot", nullable = false)
    private String connectorCodeSnapshot;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
