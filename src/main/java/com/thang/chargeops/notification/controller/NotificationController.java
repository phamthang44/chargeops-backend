package com.thang.chargeops.notification.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.notification.dto.AppNotificationResponse;
import com.thang.chargeops.notification.service.NotificationInboxService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "notifications")
@RequiredArgsConstructor
@Validated
public class NotificationController {
    private final NotificationInboxService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<List<AppNotificationResponse>>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean unread) {
        if (category != null && !category.equals("ticket")) {
            throw new IllegalArgumentException("Unsupported notification category");
        }
        return ResponseEntity.ok(ApiResult.successPage(service.list(page, size, unread)));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<Map<String, Long>>> unreadCount() {
        return ResponseEntity.ok(ApiResult.success(Map.of("count", service.unreadCount())));
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<Map<String, Boolean>>> read(@PathVariable UUID id) {
        service.markRead(id);
        return ResponseEntity.ok(ApiResult.success(Map.of("read", true)));
    }

    @PatchMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<Map<String, Boolean>>> readAll() {
        service.markAllRead();
        return ResponseEntity.ok(ApiResult.success(Map.of("read", true)));
    }
}
