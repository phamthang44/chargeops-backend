package com.thang.chargeops.station.dto.license.response;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusActorType;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Builder
@Getter
@Setter
public class LicenseStatusEventResponse {

    private UUID id;
    private UUID licenseId;
    private LicenseStatusEventType eventType;
    private LicenseStatus fromStatus;
    private LicenseStatus toStatus;
    private String reason;
    private LicenseStatusActorType actorType;
    private String performedByName;
    private Instant performedAt;

}
