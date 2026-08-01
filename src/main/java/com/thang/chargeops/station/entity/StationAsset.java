package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import com.thang.chargeops.common.enums.StationAssetType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE station_assets SET deleted_at = now() WHERE id = ?")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "station_assets", indexes = {
        @Index(name = "idx_station_assets_station_id", columnList = "station_id"),
        @Index(name = "idx_station_assets_station_order", columnList = "station_id, display_order")
})
public class StationAsset extends SoftDeletableEntity {

    @JoinColumn(name = "station_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 30)
    private StationAssetType assetType;

    @Column(name = "asset_url", nullable = false, length = 500)
    private String assetUrl;

    @Column(name = "storage_key", length = 255)
    private String storageKey;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryAsset;
}
