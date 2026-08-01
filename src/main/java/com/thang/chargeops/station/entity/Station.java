package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.profile.UserProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.ArrayList;
import java.util.List;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE stations SET deleted_at = now() WHERE id = ?")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter @Setter
@Entity
@Table(name = "stations", indexes = {
        @Index(name = "idx_stations_owner_id", columnList = "owner_id"),
        @Index(name = "idx_stations_status", columnList = "status")
})
public class Station extends SoftDeletableEntity {

    @JoinColumn(name = "owner_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserProfile owner;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "address", nullable = false, length = 200)
    private String address;

    @Column(name = "location", nullable = false, length = 100)
    private String location;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StationStatus status;

    @Builder.Default
    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, createdAt ASC")
    private List<StationAsset> assets = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek ASC, openTime ASC")
    private List<StationOperatingPeriod> operatingPeriods = new ArrayList<>();

    public void addAsset(StationAsset asset) {
        assets.add(asset);
        asset.setStation(this);
    }

    public void removeAsset(StationAsset asset) {
        assets.remove(asset);
        asset.setStation(null);
    }

    public void addOperatingPeriod(StationOperatingPeriod period) {
        operatingPeriods.add(period);
        period.setStation(this);
    }

    public void removeOperatingPeriod(StationOperatingPeriod period) {
        operatingPeriods.remove(period);
        period.setStation(null);
    }

}
