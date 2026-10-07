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
@PreAuthorize("isAuthenticated()")
public class NotificationController {
    private final NotificationInboxService service;

    @GetMapping
    public ResponseEntity<ApiResult<List<AppNotificationResponse>>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(required = false) String context,
            @RequestParam(required = false) UUID stationId) {
        return ResponseEntity.ok(ApiResult.successPage(service.list(page, size, unread, context, category, stationId)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResult<Map<String, Long>>> unreadCount(
            @RequestParam(required = false) String context, @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID stationId) {
        return ResponseEntity.ok(ApiResult.success(Map.of("count", service.unreadCount(context, category, stationId))));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResult<Map<String, Boolean>>> read(@PathVariable UUID id,
            @RequestParam(required = false) String context, @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID stationId) {
        service.markRead(id, context, category, stationId);
        return ResponseEntity.ok(ApiResult.success(Map.of("read", true)));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResult<Map<String, Boolean>>> readAll(
            @RequestParam(required = false) String context, @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID stationId) {
        service.markAllRead(context, category, stationId);
        return ResponseEntity.ok(ApiResult.success(Map.of("read", true)));
    }

    @PatchMapping("/{id}/dismiss")
    public ResponseEntity<ApiResult<Map<String, Boolean>>> dismiss(@PathVariable UUID id,
            @RequestParam(required = false) String context, @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID stationId) {
        service.dismiss(id, context, category, stationId);
        return ResponseEntity.ok(ApiResult.success(Map.of("dismissed", true)));
    }
}
