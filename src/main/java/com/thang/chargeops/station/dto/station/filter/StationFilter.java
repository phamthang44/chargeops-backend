package com.thang.chargeops.station.dto.station.filter;

import com.thang.chargeops.common.enums.StationStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StationFilter {
    private String search;
    private StationStatus status;
    private String provinceCode;
}
