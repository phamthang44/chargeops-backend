package com.thang.chargeops.station.service.support;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.service.model.OperatingWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StationOperatingHoursResolver {

    private final StationOperatingScheduleRepository scheduleRepository;
    private static final ZoneId SYSTEM_ZONE_ID = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);

    /**
     * Lấy schedule đang có hiệu lực tại thời điểm request.
     * Không có schedule được biểu diễn bằng null; public discovery sẽ coi
     * station là đang đóng thay vì tự đoán một khung giờ mặc định.
     */
    public StationOperatingSchedule findActiveSchedule(UUID stationId, Instant at) {
        return scheduleRepository.findActiveByStationId(stationId, at)
                .orElse(null);
    }

    /**
     * Xác định station có mở tại thời điểm at hay không:
     * - schedule null/hết hiệu lực: đóng;
     * - open24Hours: luôn mở;
     * - ngày thường: openTime <= localTime < closeTime;
     * - qua đêm: xét cả period hôm nay và period ngày hôm trước.
     */
    public boolean isOpenAt(StationOperatingSchedule schedule, Instant at) {
        if (schedule == null || at == null) {
            return false;
        }

        // Schedule hết hiệu lực thì không thể dùng để xác định open-now.
        if (!schedule.isActive(at)) {
            return false;
        }

        if (schedule.isOpen24Hours()) {
            return true;
        }
        ZonedDateTime localAt = at.atZone(SYSTEM_ZONE_ID);
        StationDayOfWeek today = StationDayOfWeek.valueOf(
                localAt.getDayOfWeek().name()
        );
        StationDayOfWeek yesterday = StationDayOfWeek.valueOf(
                localAt.minusDays(1).getDayOfWeek().name()
        );
        LocalTime currentTime = localAt.toLocalTime();
        return schedule.getPeriods().stream()
                .filter(StationOperatingPeriod::isEnabled)
                .anyMatch(period -> isInsideOperatingPeriod(
                        period,
                        today,
                        yesterday,
                        currentTime
                ));
    }

    /**
     * Chuyển schedule hiện tại thành các cửa sổ hoạt động nằm trong đúng ngày
     * local mà frontend yêu cầu. Ca qua đêm được tách theo ranh giới ngày để
     * response của mỗi request date luôn nằm trong [dayStart, nextDayStart).
     */
    public List<OperatingWindow> resolveOperatingWindows(
            StationOperatingSchedule schedule,
            LocalDate date
    ) {
        if (schedule == null || date == null) {
            return List.of();
        }

        Instant dayStart = date.atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        if (schedule.isOpen24Hours()) {
            return List.of(new OperatingWindow(dayStart, dayEnd));
        }

        StationDayOfWeek today = StationDayOfWeek.valueOf(
                date.getDayOfWeek().name()
        );
        StationDayOfWeek yesterday = StationDayOfWeek.valueOf(
                date.minusDays(1).getDayOfWeek().name()
        );
        StationOperatingPeriod todayPeriod = enabledPeriod(schedule, today);
        StationOperatingPeriod yesterdayPeriod = enabledPeriod(schedule, yesterday);

        List<OperatingWindow> windows = new ArrayList<>();
        addPreviousOvernightWindow(windows, date, yesterdayPeriod, dayStart);
        addTodayWindow(windows, date, todayPeriod, dayEnd);
        return mergeWindows(windows);
    }

    private StationOperatingPeriod enabledPeriod(
            StationOperatingSchedule schedule,
            StationDayOfWeek day
    ) {
        return schedule.getPeriods().stream()
                .filter(StationOperatingPeriod::isEnabled)
                .filter(period -> period.getDayOfWeek() == day)
                .findFirst()
                .orElse(null);
    }

    private void addPreviousOvernightWindow(
            List<OperatingWindow> windows,
            LocalDate date,
            StationOperatingPeriod period,
            Instant dayStart
    ) {
        if (!isOvernight(period)) {
            return;
        }

        Instant endAt = date.atTime(period.getCloseTime())
                .atZone(SYSTEM_ZONE_ID)
                .toInstant();
        if (dayStart.isBefore(endAt)) {
            windows.add(new OperatingWindow(dayStart, endAt));
        }
    }

    private void addTodayWindow(
            List<OperatingWindow> windows,
            LocalDate date,
            StationOperatingPeriod period,
            Instant dayEnd
    ) {
        if (period == null || period.getOpenTime() == null || period.getCloseTime() == null) {
            return;
        }

        Instant startAt = date.atTime(period.getOpenTime())
                .atZone(SYSTEM_ZONE_ID)
                .toInstant();
        Instant endAt = isOvernight(period)
                ? dayEnd
                : date.atTime(period.getCloseTime())
                        .atZone(SYSTEM_ZONE_ID)
                        .toInstant();
        if (startAt.isBefore(endAt)) {
            windows.add(new OperatingWindow(startAt, endAt));
        }
    }

    private boolean isOvernight(StationOperatingPeriod period) {
        return period != null
                && period.getOpenTime() != null
                && period.getCloseTime() != null
                && period.getOpenTime().isAfter(period.getCloseTime());
    }

    private List<OperatingWindow> mergeWindows(List<OperatingWindow> windows) {
        if (windows.size() < 2) {
            return List.copyOf(windows);
        }

        List<OperatingWindow> sorted = windows.stream()
                .sorted(Comparator.comparing(OperatingWindow::startAt))
                .toList();
        List<OperatingWindow> merged = new ArrayList<>();
        OperatingWindow current = sorted.getFirst();

        for (int index = 1; index < sorted.size(); index++) {
            OperatingWindow next = sorted.get(index);
            if (!next.startAt().isAfter(current.endAt())) {
                current = new OperatingWindow(
                        current.startAt(),
                        current.endAt().isAfter(next.endAt())
                                ? current.endAt()
                                : next.endAt()
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    private boolean isInsideOperatingPeriod(
            StationOperatingPeriod period,
            StationDayOfWeek today,
            StationDayOfWeek yesterday,
            LocalTime currentTime
    ) {
        LocalTime openTime = period.getOpenTime();
        LocalTime closeTime = period.getCloseTime();

        if (openTime == null || closeTime == null) {
            return false;
        }

        // Ví dụ 08:00 → 22:00
        if (openTime.isBefore(closeTime)) {
            return period.getDayOfWeek() == today
                    && !currentTime.isBefore(openTime)
                    && currentTime.isBefore(closeTime);
        }

        // Ví dụ Chủ nhật 22:00 → Thứ hai 02:00
        return (period.getDayOfWeek() == today
                && !currentTime.isBefore(openTime))
                || (period.getDayOfWeek() == yesterday
                && currentTime.isBefore(closeTime));
    }
}
