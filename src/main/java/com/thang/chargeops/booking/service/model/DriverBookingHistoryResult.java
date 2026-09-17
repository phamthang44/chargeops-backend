package com.thang.chargeops.booking.service.model;

import com.thang.chargeops.booking.dto.response.DriverBookingListItemResponse;
import org.springframework.data.domain.Page;

import java.util.Map;
import java.util.Objects;

/** Application result used to build the shared paginated API envelope. */
public record DriverBookingHistoryResult(
        Page<DriverBookingListItemResponse> page,
        Map<String, Long> counts
) {
    public DriverBookingHistoryResult {
        Objects.requireNonNull(page, "page must not be null");
        counts = Map.copyOf(counts);
    }
}
