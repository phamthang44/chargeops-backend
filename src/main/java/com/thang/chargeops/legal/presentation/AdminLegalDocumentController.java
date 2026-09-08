package com.thang.chargeops.legal.presentation;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.TargetAudience;
import com.thang.chargeops.legal.dto.CreateLegalDocumentRequest;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.dto.UpdateLegalDocumentRequest;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import com.thang.chargeops.legal.service.LegalDocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin management endpoints for CRUD on legal documents.
 * Contract: /api/v1/admin/legal-documents/**
 */
@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/legal-documents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminLegalDocumentController {

    private final LegalDocumentService legalDocumentService;

    @GetMapping
    public ResponseEntity<ApiResult<List<LegalDocumentSummaryResponse>>> getDocuments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LegalDocType docType,
            @RequestParam(required = false) TargetAudience audience,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 10, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        LegalDocumentFilter filter = LegalDocumentFilter.builder()
                .search(search)
                .docType(docType)
                .audience(audience)
                .active(active)
                .build();

        Page<LegalDocumentSummaryResponse> page = legalDocumentService.searchDocuments(filter, pageable);
        return ResponseEntity.ok(ApiResult.successPage(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<LegalDocumentDetailResponse>> getDocumentById(@PathVariable UUID id) {
        LegalDocumentDetailResponse document = legalDocumentService.getDocumentById(id);
        return ResponseEntity.ok(ApiResult.success(document));
    }

    @PostMapping
    public ResponseEntity<ApiResult<LegalDocumentDetailResponse>> createDocument(@Valid @RequestBody CreateLegalDocumentRequest request) {
        LegalDocumentDetailResponse created = legalDocumentService.createDocument(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<LegalDocumentDetailResponse>> updateDocument(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLegalDocumentRequest request
    ) {
        LegalDocumentDetailResponse updated = legalDocumentService.updateDocument(id, request);
        return ResponseEntity.ok(ApiResult.success(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        legalDocumentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
