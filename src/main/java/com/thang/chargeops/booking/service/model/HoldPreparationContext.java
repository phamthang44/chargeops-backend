package com.thang.chargeops.booking.service.model;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;

import java.time.Instant;

/**
 * Context chứa các đối tượng đã được khóa và thời điểm quyết định hợp lệ trong transaction.
 */
public record HoldPreparationContext(
        UserProfile driver,
        Connector connector,
        Instant decisionAt
) {}
