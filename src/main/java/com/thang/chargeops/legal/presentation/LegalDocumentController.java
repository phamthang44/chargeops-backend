package com.thang.chargeops.legal.presentation;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.TargetAudience;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import com.thang.chargeops.legal.service.LegalDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public and read-only endpoints for drivers, owners, and guests to read legal documents.
 * Contract: GET /api/v1/legal-documents, GET /api/v1/legal-documents/{slug}
 */
@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "legal-documents")
@RequiredArgsConstructor
public class LegalDocumentController {

    private final LegalDocumentService legalDocumentService;

    @GetMapping
    public ResponseEntity<ApiResult<List<LegalDocumentSummaryResponse>>> getDocuments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LegalDocType docType,
            @RequestParam(required = false) TargetAudience audience,
            @PageableDefault(size = 10, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        LegalDocumentFilter filter = LegalDocumentFilter.builder()
                .search(search)
                .docType(docType)
                .audience(audience)
                .active(Boolean.TRUE)
                .build();

        Page<LegalDocumentSummaryResponse> page = legalDocumentService.searchDocuments(filter, pageable);
        return ResponseEntity.ok(ApiResult.successPage(page));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResult<LegalDocumentDetailResponse>> getDocumentBySlug(@PathVariable String slug) {
        LegalDocumentDetailResponse document = legalDocumentService.getDocumentBySlug(slug);
        return ResponseEntity.ok(ApiResult.success(document));
    }
}
