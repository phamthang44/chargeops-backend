package com.thang.chargeops.legal.service;

import com.thang.chargeops.legal.dto.CreateLegalDocumentRequest;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.dto.UpdateLegalDocumentRequest;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface LegalDocumentService {

    Page<LegalDocumentSummaryResponse> searchDocuments(LegalDocumentFilter filter, Pageable pageable);

    LegalDocumentDetailResponse getDocumentBySlug(String slug);

    LegalDocumentDetailResponse getDocumentById(UUID id);

    LegalDocumentDetailResponse createDocument(CreateLegalDocumentRequest request);

    LegalDocumentDetailResponse updateDocument(UUID id, UpdateLegalDocumentRequest request);

    void deleteDocument(UUID id);
}
