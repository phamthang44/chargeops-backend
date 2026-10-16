package com.thang.chargeops.support.service.impl;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.response.AdminDashboardApprovalItem;
import com.thang.chargeops.support.dto.response.AdminDashboardQueuePage;
import com.thang.chargeops.support.dto.response.AdminDashboardTicketItem;
import com.thang.chargeops.support.dto.response.AdminOperationsSummaryResponse;
import com.thang.chargeops.support.model.AdminDashboardQueueScope;
import com.thang.chargeops.support.model.AdminDashboardSort;
import com.thang.chargeops.support.repository.AdminDashboardReadRepository;
import com.thang.chargeops.support.service.AdminDashboardQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminDashboardQueueServiceImpl implements AdminDashboardQueueService {
    private final AdminDashboardReadRepository queries;
    private final CurrentProfileProvider currentProfile;
    private final Clock applicationClock;

    @Override
    public AdminOperationsSummaryResponse summary() {
        requireActiveAdminProfile();
        var generatedAt = applicationClock.instant();
        return new AdminOperationsSummaryResponse(queries.countStations(StationStatus.ACTIVE),
                queries.countStations(StationStatus.PENDING_APPROVAL), queries.countPlatform(),
                queries.countEscalated(), generatedAt);
    }

    @Override
    public AdminDashboardQueuePage<AdminDashboardApprovalItem> approvals(int page, int size, AdminDashboardSort sort) {
        requireActiveAdminProfile();
        var pageable = pageRequest(page, size, sort);
        var generatedAt = applicationClock.instant();
        return new AdminDashboardQueuePage<>(queries.approvals(pageable, sort,
                LocaleContextHolder.getLocale().getLanguage()), generatedAt,
                AdminDashboardQueueScope.PENDING_APPROVALS, sort);
    }

    @Override
    public AdminDashboardQueuePage<AdminDashboardTicketItem> platform(int page, int size, AdminDashboardSort sort) {
        requireActiveAdminProfile();
        var pageable = pageRequest(page, size, sort);
        var generatedAt = applicationClock.instant();
        return new AdminDashboardQueuePage<>(queries.platform(pageable, sort), generatedAt,
                AdminDashboardQueueScope.PLATFORM_TICKETS, sort);
    }

    @Override
    public AdminDashboardQueuePage<AdminDashboardTicketItem> escalated(int page, int size, AdminDashboardSort sort) {
        requireActiveAdminProfile();
        var pageable = pageRequest(page, size, sort);
        var generatedAt = applicationClock.instant();
        return new AdminDashboardQueuePage<>(queries.escalated(pageable, sort), generatedAt,
                AdminDashboardQueueScope.ESCALATED_CASES, sort);
    }

    private void requireActiveAdminProfile() {
        if (currentProfile.requireProfile().getStatus() != UserStatus.ACTIVE) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }

    private static PageRequest pageRequest(int page, int size, AdminDashboardSort sort) {
        if (page < 1 || size < 1 || size > 100 || sort == null || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        return PageRequest.of(page - 1, size);
    }
}
