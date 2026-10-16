package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.support.model.AdminDashboardQueueScope;
import com.thang.chargeops.support.model.AdminDashboardSort;
import org.springframework.data.domain.Page;

import java.time.Instant;

/** Internal transport between service and controller; data remains an array on the wire. */
public record AdminDashboardQueuePage<T>(
        Page<T> page,
        Instant generatedAt,
        AdminDashboardQueueScope scope,
        AdminDashboardSort sort
) {}
