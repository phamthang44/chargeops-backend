package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationAssetType;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class StationAssetResponse {

    private StationAssetType assetType;
    private String assetUrl;
    private boolean isPrimary;
    private String storageKey;
    private int displayOrder;
    private String altText;

}
