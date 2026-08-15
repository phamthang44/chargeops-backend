package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ApprovalErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StationApprovalPolicyImpl implements StationApprovalPolicy {

    private final LicenseRepository licenseRepository;

    @Override
    public void requireCanBeApproved(Station station) {
        if (station.getStatus() != StationStatus.PENDING_APPROVAL) {
            throw new AppException(
                    ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL,
                    station.getId(),
                    station.getStatus()
            );
        }

        if (!licenseRepository.existsActiveLicenseForStation(station.getId(), Instant.now())) {
            throw new AppException(ApprovalErrorCode.ACTIVE_LICENSE_REQUIRED, station.getId());
        }
    }

//    private void requireReasonWhenNeeded(StationStatusEventType eventType, String reason) {
//        if (!eventType.isReasonRequired() || hasText(reason)) {
//            return;
//        }
//
//        if (eventType == StationStatusEventType.REJECTED) {
//            throw new AppException(ApprovalErrorCode.REJECTION_REASON_REQUIRED);
//        }
//
//        throw new AppException(StationErrorCode.STATION_SUSPENSION_REASON_REQUIRED);
//    }
//
//    private boolean hasText(String value) {
//        return value != null && !value.isBlank();
//    }
//
//    private String requireValidRejectionReason(String reason) {
//        if (reason == null || reason.isBlank()) {
//            throw new AppException(ApprovalErrorCode.REJECTION_REASON_REQUIRED);
//        }
//
//        return reason.trim();
//    }

    @Override
    public void requireCanBeRejected(Station station, String reason) {
        if (station.getStatus() != StationStatus.PENDING_APPROVAL) {
            throw new AppException(
                    ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL,
                    station.getId(),
                    station.getStatus()
            );
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException(ApprovalErrorCode.REJECTION_REASON_REQUIRED);
        }
    }
}
