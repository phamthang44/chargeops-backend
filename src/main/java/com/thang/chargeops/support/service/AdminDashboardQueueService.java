package com.thang.chargeops.support.service;

import com.thang.chargeops.support.dto.response.AdminDashboardApprovalItem;
import com.thang.chargeops.support.dto.response.AdminDashboardQueuePage;
import com.thang.chargeops.support.dto.response.AdminDashboardTicketItem;
import com.thang.chargeops.support.dto.response.AdminOperationsSummaryResponse;
import com.thang.chargeops.support.model.AdminDashboardSort;

public interface AdminDashboardQueueService {
    AdminOperationsSummaryResponse summary();

    AdminDashboardQueuePage<AdminDashboardApprovalItem> approvals(int page, int size, AdminDashboardSort sort);

    AdminDashboardQueuePage<AdminDashboardTicketItem> platform(int page, int size, AdminDashboardSort sort);

    AdminDashboardQueuePage<AdminDashboardTicketItem> escalated(int page, int size, AdminDashboardSort sort);
}
