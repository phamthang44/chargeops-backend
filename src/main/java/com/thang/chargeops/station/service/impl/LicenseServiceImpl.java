package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.LicenseErrorCode;
import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.license.request.IssueLicenseRequest;
import com.thang.chargeops.station.dto.license.request.RenewLicenseRequest;
import com.thang.chargeops.station.dto.license.response.*;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.exception.LicenseDomainException;
import com.thang.chargeops.station.mapper.LicenseMapper;
import com.thang.chargeops.station.policy.LicenseLifeCyclePolicy;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.repository.specs.LicenseSpecification;
import com.thang.chargeops.station.service.LicenseService;
import com.thang.chargeops.station.service.LicenseStatusEventService;
import com.thang.chargeops.station.service.StationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseServiceImpl implements LicenseService {

    private static final String ACTIVE_LICENSE_UNIQUE_CONSTRAINT =
            "ux_licenses_one_active_per_station";
    private static final String LICENSE_CODE_FORMAT = "LIC-%06d";

    private final StationService stationService;
    private final LicenseMapper licenseMapper;
    private final LicenseRepository licenseRepository;
    private final LicenseStatusEventService licenseStatusEventService;
    private final CurrentProfileProvider currentProfileProvider;
    private final UserProfileService userProfileService;
    private final LicenseLifeCyclePolicy licenseLifeCyclePolicy;

    //khả năng future activation thì để thành future enhancement.
    @Override
    @Transactional
    public IssueLicenseResponse issueLicense(UUID stationId, IssueLicenseRequest request) {
        UserProfile admin = currentProfileProvider.getProfileReference();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "issueLicense", admin.getId(), "stationId=" + stationId + ", request=" + request);

        try {
            Station station = stationService.getStationById(stationId);

            Instant issuedAt = Instant.now();

            // This pre-check deliberately mirrors the DB partial unique index.
            // Effective-at-time queries are correct for eligibility reads, but a
            // stale ACTIVE row must be reconciled by the expiry job before a new
            // ACTIVE row can be inserted.
            if (licenseRepository.existsPersistedActiveLicenseForStation(station.getId())) {
                throw new AppException(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS, stationId);
            }

            String licenseCode = nextLicenseCode();
            License license = License.issue(
                    station,
                    request.plan(),
                    issuedAt,
                    licenseCode
            );

            License savedLicense = licenseRepository.save(license);
            licenseStatusEventService.recordLicenseStatusEvent(LicenseStatusEvent.recordedByUser(
                    savedLicense,
                    LicenseStatusEventType.ISSUED,
                    null,
                    LicenseStatus.PENDING,
                    admin,
                    issuedAt,
                    null
            ));

            LicenseStatus previousStatus = savedLicense.getStatus();
            savedLicense.activate(issuedAt);

            licenseStatusEventService.recordLicenseStatusEvent(LicenseStatusEvent.recordedByUser(
                    savedLicense,
                    LicenseStatusEventType.ACTIVATED,
                    previousStatus,
                    savedLicense.getStatus(),
                    admin,
                    issuedAt,
                    null
            ));
            licenseRepository.flush();
            //ko cần save() lần 2 vì bị managed bởi JPA dirty checking sẽ cập nhật trạng thái
            log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "issueLicense", admin.getId(), "licenseId=" + savedLicense.getId() + ", stationId=" + stationId + ", status=" + savedLicense.getStatus());
            return licenseMapper.toIssueLicenseResponse(savedLicense);
        } catch (DataIntegrityViolationException e) {
            if (!isActiveLicenseUniqueConstraintViolation(e)) {
                throw e;
            }
            log.warn("[{}] - Method: issueLicense - stationId: {} - Error: active license conflict", LogConstant.BIZ_ERROR, stationId, e);
            throw new AppException(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS, stationId);
        }
    }

    @Override
    @Transactional
    public void suspendLicense(UUID licenseId, String reason) {
        UserProfile admin = currentProfileProvider.getProfileReference();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START,
                "suspendLicense", admin.getId(), "licenseId=" + licenseId);

        Instant at = Instant.now();
        String normalizedReason = normalizeRequiredReason(reason);
        License license = requireLicense(licenseId);

        LicenseStatus fromStatus = license.getStatus();
        try {
            // Stable extension point for future cross-domain restrictions.
            licenseLifeCyclePolicy.requireCanSuspend(license, at);
            // Entity owns the state machine and effective-window invariant.
            license.suspend(at);
        } catch (LicenseDomainException exception) {
            throw translateDomainException(
                    exception,
                    licenseId,
                    fromStatus,
                    LicenseStatus.SUSPENDED
            );
        }

        licenseStatusEventService.recordLicenseStatusEvent(LicenseStatusEvent.recordedByUser(
                license,
                LicenseStatusEventType.SUSPENDED,
                fromStatus,
                license.getStatus(),
                admin,
                at,
                normalizedReason
        ));

        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS,
                "suspendLicense", admin.getId(), "licenseId=" + licenseId);
    }

    @Override
    @Transactional
    public void cancelLicense(UUID licenseId, String reason) {
        UserProfile admin = currentProfileProvider.getProfileReference();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START,
                "cancelLicense", admin.getId(), "licenseId=" + licenseId);

        Instant at = Instant.now();
        String normalizedReason = normalizeRequiredReason(reason);
        License license = requireLicense(licenseId);
        LicenseStatus fromStatus = license.getStatus();

        try {
            // Cancellation is terminal. Cross-domain blockers are intentionally
            // delegated to this policy seam as those modules are introduced.
            licenseLifeCyclePolicy.requireCanCancel(license, at);
            license.cancel();
        } catch (LicenseDomainException exception) {
            throw translateDomainException(
                    exception,
                    licenseId,
                    fromStatus,
                    LicenseStatus.CANCELLED
            );
        }

        licenseStatusEventService.recordLicenseStatusEvent(LicenseStatusEvent.recordedByUser(
                license,
                LicenseStatusEventType.CANCELLED,
                fromStatus,
                license.getStatus(),
                admin,
                at,
                normalizedReason
        ));

        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS,
                "cancelLicense", admin.getId(), "licenseId=" + licenseId);
    }

    @Override
    @Transactional
    public void reactivateLicense(UUID licenseId, String reason) {
        UserProfile admin = currentProfileProvider.getProfileReference();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START,
                "reactivateLicense", admin.getId(), "licenseId=" + licenseId);

        Instant at = Instant.now();
        String normalizedReason = normalizeRequiredReason(reason);
        License license = requireLicense(licenseId);
        LicenseStatus fromStatus = license.getStatus();
        try {
            licenseLifeCyclePolicy.requireCanReactivate(license, at);
            license.reactivate(at);

            licenseStatusEventService.recordLicenseStatusEvent(LicenseStatusEvent.recordedByUser(
                    license,
                    LicenseStatusEventType.REACTIVATED,
                    fromStatus,
                    license.getStatus(),
                    admin,
                    at,
                    normalizedReason
            ));

            // Force DB unique/optimistic conflict inside this translation boundary.
            licenseRepository.flush();
        } catch (LicenseDomainException exception) {
            throw translateDomainException(
                    exception,
                    licenseId,
                    fromStatus,
                    LicenseStatus.ACTIVE
            );
        } catch (DataIntegrityViolationException exception) {
            if (isActiveLicenseUniqueConstraintViolation(exception)) {
                throw new AppException(
                        LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS,
                        license.getStation().getId()
                );
            }
            throw exception;
        }

        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS,
                "reactivateLicense", admin.getId(), "licenseId=" + licenseId);
    }

    @Override
    @Transactional
    public RenewLicenseResponse renewLicense(UUID sourceLicenseId, RenewLicenseRequest request) {
        UserProfile admin = currentProfileProvider.getProfileReference();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START,
                "renewLicense", admin.getId(), "licenseId=" + sourceLicenseId);

        // Chụp thời gian đúng một lần để toàn bộ quyết định trong transaction
        // (kiểm tra policy, chọn startAt và ghi event) dùng cùng một mốc thời gian.
        Instant now = Instant.now();

        // sourceLicense là kỳ hiện tại/cũ mà admin chọn để gia hạn. Renew không
        // kéo dài row này mà luôn tạo một row License mới làm kỳ kế tiếp.
        License sourceLicense = requireLicense(sourceLicenseId);

        // Policy xử lý các luật cần nhìn sang nhiều row: trạng thái được renew,
        // chưa có kỳ kế tiếp và source phải là kỳ mới nhất của Station.
        licenseLifeCyclePolicy.requireCanRenew(sourceLicense, now);

        // ACTIVE: kỳ mới nối tiếp chính xác sau expiresAt để không chồng lấn.
        // EXPIRED: bắt đầu từ now vì khoảng thời gian đã hết không được hồi tố.
        Instant renewalStartAt =
                sourceLicense.getStatus() == LicenseStatus.ACTIVE
                        ? sourceLicense.getExpiresAt()
                        : now;

        // Mỗi kỳ là một License độc lập nên có code riêng để tra cứu/audit.
        String licenseCode = nextLicenseCode();

        // Factory tạo License mới ở PENDING và gắn renewedFrom = sourceLicense.
        // Liên kết này cùng DB unique constraint ngăn một source có hai successor.
        License renewalLicense = License.renewFrom(sourceLicense, request.plan(), renewalStartAt, licenseCode);

        License savedRenewal = licenseRepository.save(renewalLicense);

        // Mọi renewal đều được phát hành trước, vì vậy luôn có ISSUED event dù
        // nó sẽ ACTIVE ngay hay phải chờ scheduler kích hoạt trong tương lai.
        licenseStatusEventService.recordLicenseStatusEvent(
                LicenseStatusEvent.recordedByUser(
                        savedRenewal,
                        LicenseStatusEventType.ISSUED,
                        null,
                        LicenseStatus.PENDING,
                        admin,
                        now,
                        null
                )
        );

        // Source đã EXPIRED thì startAt = now, do đó kỳ mới đủ điều kiện ACTIVE
        // ngay trong request. Source ACTIVE thì kỳ mới giữ PENDING đến startAt.
        if (sourceLicense.getStatus() == LicenseStatus.EXPIRED) {
            savedRenewal.activate(now);
            licenseStatusEventService.recordLicenseStatusEvent(LicenseStatusEvent.recordedByUser(
                    savedRenewal,
                    LicenseStatusEventType.ACTIVATED,
                    LicenseStatus.PENDING,
                    LicenseStatus.ACTIVE,
                    admin,
                    now,
                    null
            ));
        }

        // Ép SQL chạy trước khi rời transaction để unique/optimistic conflict
        // xuất hiện trong ranh giới xử lý của command renew.
        licenseRepository.flush();

        // API trả về kỳ mới, không trả lại sourceLicense mà admin vừa chọn.
        return licenseMapper.toRenewLicenseResponse(savedRenewal);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminLicenseListItemResponse> searchLicenses(int pageNo, int pageSize, LicenseFilter filter) {
        Pageable pageable = PageRequest.of(getPageNo(pageNo), pageSize);

        Specification<License> specificationLicense = LicenseSpecification.filter(filter);

        Page<License> licensePage = licenseRepository.findAll(specificationLicense, pageable);

        return licensePage.map(licenseMapper::toListItemResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminLicenseDetailResponse getLicenseDetail(UUID licenseId) {

        License license = licenseRepository.findWithDetailsById(licenseId).orElseThrow(() -> new AppException(LicenseErrorCode.LICENSE_NOT_FOUND, licenseId));

        String recordedByName = resolveRecordedByName(license.getCreatedBy());

        return licenseMapper.toDetailResponse(license, recordedByName);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminLicenseListItemResponse> getStationLicenseHistory(UUID stationId) {
        Station station = stationService.getStationById(stationId);

        List<License> licenses = licenseRepository.findStationLicenseHistory(station.getId());

        return licenseMapper.toListItemResponseList(licenses);
    }

    private String resolveRecordedByName(UUID createdById) {
        if (createdById == null) {
            return SystemConstant.SYSTEM_ACTOR;
        }
        UserProfile profile = userProfileService.getUserProfileById(createdById);
        if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
            return profile.getDisplayName();
        }
        return profile.getEmail();
    }

    private License requireLicense(UUID licenseId) {
        return licenseRepository.findById(licenseId)
                .orElseThrow(() -> new AppException(
                        LicenseErrorCode.LICENSE_NOT_FOUND,
                        licenseId
                ));
    }

    private AppException translateDomainException(
            LicenseDomainException exception,
            UUID licenseId,
            LicenseStatus fromStatus,
            LicenseStatus toStatus
    ) {
        return switch (exception.getViolation()) {
            case INVALID_TRANSITION, TERMINAL_LICENSE -> new AppException(
                    LicenseErrorCode.INVALID_STATUS_TRANSITION,
                    licenseId,
                    fromStatus,
                    toStatus
            );
            case OUTSIDE_EFFECTIVE_WINDOW -> new AppException(
                    LicenseErrorCode.LICENSE_OUTSIDE_EFFECTIVE_PERIOD,
                    licenseId
            );
        };
    }

    private String normalizeRequiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    LicenseErrorMessage.STATUS_CHANGE_REASON_REQUIRED.defaultMessage()
            );
        }

        String normalizedReason = reason.trim();
        if (normalizedReason.length() < 5 || normalizedReason.length() > 500) {
            throw new IllegalArgumentException(
                    LicenseErrorMessage.STATUS_CHANGE_REASON_SIZE.defaultMessage()
            );
        }
        return normalizedReason;
    }

    private int getPageNo(int pageNo) {
        if(pageNo <= 1) {
            pageNo = 0;
        }
        return pageNo;
    }

    private boolean isActiveLicenseUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolationException) {
                String constraintName = constraintViolationException.getConstraintName();
                if (ACTIVE_LICENSE_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraintName)) {
                    return true;
                }
            }

            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(ACTIVE_LICENSE_UNIQUE_CONSTRAINT)) {
                return true;
            }

            Throwable nextCause = cause.getCause();
            if (nextCause == cause) {
                break;
            }
            cause = nextCause;
        }
        return false;
    }

    private String nextLicenseCode() {
        return LICENSE_CODE_FORMAT.formatted(
                licenseRepository.nextLicenseCodeSequence()
        );
    }
}
