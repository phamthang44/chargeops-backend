package com.thang.chargeops.moderation.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.moderation.dto.*;
import com.thang.chargeops.moderation.model.ContentScope;
import com.thang.chargeops.moderation.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/prohibited-terms")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminProhibitedTermController {
    private final ProhibitedTermAdminService admin;
    private final ContentModerationService moderation;

    @GetMapping
    public ResponseEntity<ApiResult<List<ProhibitedTermResponse>>> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) ContentScope scope, @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(admin.list(q, scope, enabled, page, size)));
    }
    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<ProhibitedTermResponse>> get(@PathVariable UUID id) {
        return response(HttpStatus.OK, admin.get(id));
    }
    @PostMapping
    public ResponseEntity<ApiResult<ProhibitedTermResponse>> create(@Valid @RequestBody ProhibitedTermRequests.Create request) {
        return response(HttpStatus.CREATED, admin.create(request));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<ProhibitedTermResponse>> update(@PathVariable UUID id,
            @Valid @RequestBody ProhibitedTermRequests.Update request) {
        return response(HttpStatus.OK, admin.update(id, request));
    }
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResult<ProhibitedTermResponse>> status(@PathVariable UUID id,
            @Valid @RequestBody ProhibitedTermRequests.Status request) {
        return response(HttpStatus.OK, admin.setStatus(id, request));
    }
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResult<List<TermChangeResponse>>> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(admin.history(id, page, size)));
    }
    @PostMapping("/check")
    public ResponseEntity<ApiResult<ModerationCheckResponse>> check(@Valid @RequestBody ProhibitedTermRequests.Check request) {
        return response(HttpStatus.OK, moderation.check(request.scope(), request.text()));
    }
    private <T> ResponseEntity<ApiResult<T>> response(HttpStatus status, T value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResult.success(value));
    }
}
