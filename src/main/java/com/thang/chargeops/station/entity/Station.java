package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.jdbc.Expectation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@SQLRestriction("deleted_at is null")
@SQLDelete(
        sql = "UPDATE stations SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?",
        verify = Expectation.RowCount.class
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter @Setter
@Entity
@Table(name = "stations", indexes = {
        @Index(name = "idx_stations_owner_id", columnList = "owner_id"),
        @Index(name = "idx_stations_status", columnList = "status"),
        @Index(name = "idx_stations_ward_code", columnList = "ward_code")
})
public class Station extends SoftDeletableEntity {

    @Column(name = "station_code", nullable = false, unique = true, updatable = false, length = 20)
    private String stationCode;

    @JoinColumn(name = "owner_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserProfile owner;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @JoinColumn(name = "ward_code", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AdministrativeWard ward;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 6)
    private BigDecimal longitude;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Column(name = "planned_charge_point_count", nullable = false)
    private int plannedChargePointCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StationStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Builder.Default
    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, createdAt ASC")
    private List<StationAsset> assets = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "station", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("effectiveFrom DESC")
    private List<StationOperatingSchedule> operatingSchedules = new ArrayList<>();

    @OneToOne(mappedBy = "station", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private StationBookingSettings bookingSettings;

    public void addAsset(StationAsset asset) {
        assets.add(asset);
        asset.setStation(this);
    }

    public void removeAsset(StationAsset asset) {
        assets.remove(asset);
        asset.setStation(null);
    }

    public void addOperatingSchedule(StationOperatingSchedule schedule) {
        operatingSchedules.add(schedule);
        schedule.setStation(this);
    }

    public void removeOperatingSchedule(StationOperatingSchedule schedule) {
        operatingSchedules.remove(schedule);
        schedule.setStation(null);
    }

    public void setBookingSettings(StationBookingSettings bookingSettings) {
        this.bookingSettings = bookingSettings;
        if (bookingSettings != null) {
            bookingSettings.setStation(this);
        }
    }

    public java.util.Optional<StationOperatingSchedule> findActiveSchedule(java.time.Instant now) {
        return operatingSchedules.stream()
                .filter(s -> s.isActive(now))
                .findFirst();
    }

}
