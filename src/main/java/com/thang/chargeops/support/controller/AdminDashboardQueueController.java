package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.response.AdminDashboardApprovalItem;
import com.thang.chargeops.support.dto.response.AdminDashboardQueuePage;
import com.thang.chargeops.support.dto.response.AdminDashboardTicketItem;
import com.thang.chargeops.support.model.AdminDashboardSort;
import com.thang.chargeops.support.service.AdminDashboardQueueService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/dashboard/queues")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminDashboardQueueController {
    private final AdminDashboardQueueService queues;

    @GetMapping("/approvals")
    public ResponseEntity<ApiResult<List<AdminDashboardApprovalItem>>> approvals(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "OLDEST_FIRST") AdminDashboardSort sort) {
        return response(queues.approvals(page, size, sort));
    }

    @GetMapping("/platform")
    public ResponseEntity<ApiResult<List<AdminDashboardTicketItem>>> platform(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "NEWEST_FIRST") AdminDashboardSort sort) {
        return response(queues.platform(page, size, sort));
    }

    @GetMapping("/escalated")
    public ResponseEntity<ApiResult<List<AdminDashboardTicketItem>>> escalated(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "NEWEST_FIRST") AdminDashboardSort sort) {
        return response(queues.escalated(page, size, sort));
    }

    private static <T> ResponseEntity<ApiResult<List<T>>> response(AdminDashboardQueuePage<T> result) {
        var page = result.page();
        var meta = ApiResult.Meta.builder()
                .page(page.getNumber() + 1).size(page.getSize())
                .totalElements(page.getTotalElements()).totalPages(page.getTotalPages())
                .generatedAt(result.generatedAt()).scope(result.scope().name()).sort(result.sort().name())
                .build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(page.getContent(), meta));
    }
}
