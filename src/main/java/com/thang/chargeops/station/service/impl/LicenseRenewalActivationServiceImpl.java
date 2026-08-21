package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.LicenseRenewalActivationService;
import com.thang.chargeops.station.service.LicenseStatusEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LicenseRenewalActivationServiceImpl implements LicenseRenewalActivationService {

    private static final String EXPIRATION_REASON = "License validity period elapsed";
    private static final String ACTIVATION_REASON = "Renewed license validity period started";

    private final LicenseRepository licenseRepository;
    private final LicenseStatusEventService licenseStatusEventService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean activateIfDue(UUID renewedLicenseId, Instant now) {
        if (renewedLicenseId == null || now == null) {
            throw new IllegalArgumentException("Renewal activation id and time are required");
        }

        License renewal = licenseRepository.findById(renewedLicenseId).orElse(null);
        // Scheduler chỉ lấy candidate sơ bộ. Vào transaction vẫn phải kiểm tra
        // lại để job có tính idempotent và an toàn nếu admin/job khác vừa đổi row.
        if (!isDuePendingRenewal(renewal, now)) {
            return false;
        }

        License source = renewal.getRenewedFrom();

        // Kỳ kế tiếp tuyệt đối không được ACTIVE trước khi kỳ nguồn kết thúc.
        // Guard này bảo vệ rollover kể cả khi dữ liệu sai đã bypass command renew.
        if (source.getExpiresAt().isAfter(now)
                || source.getStatus() == LicenseStatus.PENDING) {
            return false;
        }

        // TODO(compliance): Trước khi activate, gọi StationLicensingControlPolicy
        // để yêu cầu control == CLEAR. Không suy ra cross-period hold từ status
        // của source: source có thể EXPIRED nhưng Station vẫn COMPLIANCE_HOLD.
        // Cho tới khi seam này được implement, code hiện tại chưa chặn rollover.

        // Có thể scheduler expiry chưa chạy hoặc chạy cùng lúc. Method này tự
        // expire source nếu cần để việc rollover không phụ thuộc thứ tự hai job.
        expireSourceIfDue(source, now);

        // Sau khi source được expire trong persistence context, kiểm tra Station
        // không có ACTIVE khác. Partial unique index vẫn là guard cuối chống race.
        if (licenseRepository.existsPersistedActiveLicenseForStation(
                renewal.getStation().getId()
        )) {
            return false;
        }

        LicenseStatus fromStatus = renewal.getStatus();
        renewal.activate(now);
        licenseStatusEventService.recordLicenseStatusEvent(
                LicenseStatusEvent.recordedBySystem(
                        renewal,
                        LicenseStatusEventType.ACTIVATED,
                        fromStatus,
                        renewal.getStatus(),
                        now,
                        ACTIVATION_REASON
                )
        );

        // Flush ngay trong REQUIRES_NEW để conflict chỉ rollback candidate hiện
        // tại; scheduler vẫn tiếp tục xử lý các License còn lại trong batch.
        licenseRepository.flush();
        return true;
    }

    private boolean isDuePendingRenewal(License renewal, Instant now) {
        return renewal != null
                && renewal.getStatus() == LicenseStatus.PENDING
                && renewal.getRenewedFrom() != null
                && !now.isBefore(renewal.getStartAt())
                && now.isBefore(renewal.getExpiresAt());
    }

    private void expireSourceIfDue(License source, Instant now) {
        if (source.getStatus() == LicenseStatus.CANCELLED
                || source.getStatus() == LicenseStatus.EXPIRED) {
            return;
        }

        LicenseStatus fromStatus = source.getStatus();
        source.markExpired(now);
        licenseStatusEventService.recordLicenseStatusEvent(
                LicenseStatusEvent.recordedBySystem(
                        source,
                        LicenseStatusEventType.EXPIRED,
                        fromStatus,
                        source.getStatus(),
                        now,
                        EXPIRATION_REASON
                )
        );
    }
}
