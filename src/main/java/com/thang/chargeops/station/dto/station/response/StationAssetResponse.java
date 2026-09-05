package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationAssetType;
import lombok.Getter;
import lombok.Setter;


import java.util.UUID;

@Getter
@Setter
public class StationAssetResponse {

    private UUID id;
    private StationAssetType assetType;
    private String assetUrl;
    private boolean isPrimary;
    private String storageKey;
    private int displayOrder;
    private String altText;

}
