package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.license.response.LicenseStatusEventResponse;
import com.thang.chargeops.station.entity.LicenseStatusEvent;

import java.util.List;
import java.util.UUID;

public interface LicenseStatusEventService {

    void recordLicenseStatusEvent(LicenseStatusEvent event);

    List<LicenseStatusEventResponse> getLicenseStatusEvents(UUID licenseId);

}
