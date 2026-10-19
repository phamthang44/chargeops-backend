package com.thang.chargeops.payment.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.dto.response.OwnerFinanceAnalyticsResponse;
import com.thang.chargeops.payment.dto.response.OwnerFinanceAnalyticsResponse.Day;
import com.thang.chargeops.payment.repository.OwnerFinanceAnalyticsRepository;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OwnerFinanceAnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final CurrentProfileProvider profiles;
    private final OwnerFinanceAnalyticsRepository reports;
    private final PaymentRepository payments;
    private final Clock applicationClock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OwnerFinanceAnalyticsResponse analytics(UUID stationId, String period) {
        if (!"7d".equals(period) && !"30d".equals(period)) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        UUID ownerId = profiles.requireProfileId();
        if (stationId != null && !reports.canReadStation(ownerId, stationId)) {
            throw new AccessDeniedException("Station is outside owner scope");
        }
        // Preserve the canonical finance fail-closed reconciliation rule.
        if (payments.countOwnerSimulatorDriftedPayments(ownerId) > 0) {
            throw new AppException(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
        }
        var now = applicationClock.instant();
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate first = today.minusDays("30d".equals(period) ? 29 : 6);
        var from = first.atStartOfDay(ZONE).toInstant();
        var to = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        var buckets = reports.daily(ownerId, stationId, from, to, now).stream()
                .collect(Collectors.toMap(Day::date, Function.identity()));
        var daily = new ArrayList<Day>();
        long gross = 0, refunded = 0;
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            Day day = buckets.getOrDefault(date, new Day(date, 0, 0, 0));
            daily.add(day);
            gross = Math.addExact(gross, day.grossVnd());
            refunded = Math.addExact(refunded, day.refundedVnd());
        }
        var stations = reports.stations(ownerId, stationId, from, to, now);
        return new OwnerFinanceAnalyticsResponse("SIMULATOR", "VND", ZONE.getId(), period, stationId,
                from, to, now, gross, refunded, Math.subtractExact(gross, refunded), daily,
                stations.items(), stations.total(), 10);
    }
}
