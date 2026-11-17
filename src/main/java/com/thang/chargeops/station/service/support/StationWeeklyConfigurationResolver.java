package com.thang.chargeops.station.service.support;

import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.service.model.OperatingWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolves the weekly commercial version that applies to each instant. */
@Component
@RequiredArgsConstructor
public class StationWeeklyConfigurationResolver {

    private final StationOperatingScheduleRepository scheduleRepository;
    private final StationOperatingHoursResolver operatingHoursResolver;

    public Optional<StationOperatingSchedule> findAt(UUID stationId, Instant at) {
        return scheduleRepository.findActiveByStationId(stationId, at);
    }

    public List<StationOperatingSchedule> findOverlapping(
            UUID stationId,
            Instant rangeStart,
            Instant rangeEnd
    ) {
        if (stationId == null || rangeStart == null || rangeEnd == null
                || !rangeStart.isBefore(rangeEnd)) {
            return List.of();
        }
        return scheduleRepository.findEffectiveVersionsOverlapping(
                stationId,
                rangeStart,
                rangeEnd
        );
    }

    public List<OperatingWindow> resolveOperatingWindows(
            UUID stationId,
            LocalDate date,
            Instant coverageStart,
            Instant coverageEnd
    ) {
        if (date == null || coverageStart == null || coverageEnd == null
                || !coverageStart.isBefore(coverageEnd)) {
            return List.of();
        }

        List<OperatingWindow> windows = new ArrayList<>();
        for (StationOperatingSchedule schedule : findOverlapping(
                stationId,
                coverageStart,
                coverageEnd
        )) {
            Instant segmentStart = laterOf(coverageStart, schedule.getEffectiveFrom());
            Instant segmentEnd = earlierOfNullable(coverageEnd, schedule.getEffectiveTo());
            if (segmentStart.isBefore(segmentEnd)) {
                windows.addAll(operatingHoursResolver.resolveOperatingWindows(
                        schedule,
                        date,
                        segmentStart,
                        segmentEnd
                ));
            }
        }
        return mergeWindows(windows);
    }

    public boolean coversInterval(UUID stationId, Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            return false;
        }

        Instant coveredUntil = startAt;
        for (StationOperatingSchedule schedule : findOverlapping(stationId, startAt, endAt)) {
            Instant segmentStart = laterOf(coveredUntil, schedule.getEffectiveFrom());
            Instant segmentEnd = earlierOfNullable(endAt, schedule.getEffectiveTo());
            if (segmentStart.isAfter(coveredUntil)) {
                return false;
            }
            if (!segmentStart.isBefore(segmentEnd)) {
                continue;
            }
            if (!operatingHoursResolver.coversInterval(schedule, segmentStart, segmentEnd)) {
                return false;
            }
            coveredUntil = segmentEnd;
            if (!coveredUntil.isBefore(endAt)) {
                return true;
            }
        }
        return false;
    }

    private List<OperatingWindow> mergeWindows(List<OperatingWindow> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<OperatingWindow> ordered = windows.stream()
                .filter(window -> window.startAt().isBefore(window.endAt()))
                .sorted(Comparator.comparing(OperatingWindow::startAt))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<OperatingWindow> merged = new ArrayList<>();
        Instant currentStart = ordered.get(0).startAt();
        Instant currentEnd = ordered.get(0).endAt();
        for (int i = 1; i < ordered.size(); i++) {
            OperatingWindow next = ordered.get(i);
            if (!next.startAt().isAfter(currentEnd)) {
                if (next.endAt().isAfter(currentEnd)) {
                    currentEnd = next.endAt();
                }
            } else {
                merged.add(new OperatingWindow(currentStart, currentEnd));
                currentStart = next.startAt();
                currentEnd = next.endAt();
            }
        }
        merged.add(new OperatingWindow(currentStart, currentEnd));
        return List.copyOf(merged);
    }

    private Instant laterOf(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private Instant earlierOfNullable(Instant left, Instant right) {
        return right == null || left.isBefore(right) ? left : right;
    }
}
