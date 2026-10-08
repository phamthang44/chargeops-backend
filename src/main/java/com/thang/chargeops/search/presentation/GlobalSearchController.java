package com.thang.chargeops.search.presentation;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.search.GlobalSearchService;
import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.dto.GlobalSearchResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate global search cho các console (admin / owner / staff).
 *
 * <p>Input thật của ô search chỉ là keyword. {@code type} là bộ lọc tùy chọn
 * người dùng chủ động chọn; scope dữ liệu do service + provider quyết định theo
 * JWT, không bao giờ nhận qua query param.
 */
@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "search")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN','OWNER','STAFF')")
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;

    @GetMapping
    public ResponseEntity<ApiResult<GlobalSearchResponse>> search(
            @RequestParam String q,
            @RequestParam(required = false) GlobalSearchType type,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ResponseEntity.ok(ApiResult.success(globalSearchService.search(q, type, limit)));
    }
}
