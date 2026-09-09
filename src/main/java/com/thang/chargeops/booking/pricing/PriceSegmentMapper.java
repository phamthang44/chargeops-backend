package com.thang.chargeops.booking.pricing;

import com.thang.chargeops.station.service.model.StationPriceRange;

import java.time.Duration;
import java.util.List;

public final class PriceSegmentMapper {

    private PriceSegmentMapper() {
    }

    public static PriceSegment fromStationPriceRange(StationPriceRange range) {
        int durationMin = Math.toIntExact(
                Duration.between(range.startAt(), range.endAt()).toMinutes()
        );
        return new PriceSegment(
                range.startAt(),
                range.endAt(),
                durationMin,
                range.periodCode().name(),
                range.periodCode(),
                range.rateVndPerKwh()
        );
    }

    public static List<PriceSegment> fromStationPriceRanges(
            List<StationPriceRange> ranges
    ) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        return ranges.stream()
                .map(PriceSegmentMapper::fromStationPriceRange)
                .toList();
    }
}
