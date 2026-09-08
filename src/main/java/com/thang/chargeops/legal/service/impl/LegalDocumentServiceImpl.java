package com.thang.chargeops.legal.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.legal.domain.LegalDocument;
import com.thang.chargeops.legal.dto.CreateLegalDocumentRequest;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.dto.UpdateLegalDocumentRequest;
import com.thang.chargeops.legal.repository.LegalDocumentRepository;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import com.thang.chargeops.legal.repository.specs.LegalDocumentSpecification;
import com.thang.chargeops.legal.service.LegalDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import com.thang.chargeops.legal.service.LegalDocumentKeywords;
import org.springframework.data.domain.PageRequest;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LegalDocumentServiceImpl implements LegalDocumentService {

    private final LegalDocumentRepository legalDocumentRepository;

    @Override
    public Page<LegalDocumentSummaryResponse> searchDocuments(LegalDocumentFilter filter, Pageable pageable) {
        Pageable effectivePageable = pageable;
        if (filter != null && hasText(filter.search()) && pageable != null && pageable.getSort().isSorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        }
        return legalDocumentRepository.findAll(LegalDocumentSpecification.filter(filter), effectivePageable)
                .map(LegalDocumentSummaryResponse::fromEntity);
    }

    @Override
    public LegalDocumentDetailResponse getDocumentBySlug(String slug) {
        LegalDocument doc = legalDocumentRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Document not found with slug: " + slug));
        return LegalDocumentDetailResponse.fromEntity(doc);
    }

    @Override
    public LegalDocumentDetailResponse getDocumentById(UUID id) {
        LegalDocument doc = legalDocumentRepository.findById(id)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Document not found with id: " + id));
        return LegalDocumentDetailResponse.fromEntity(doc);
    }

    @Override
    @Transactional
    public LegalDocumentDetailResponse createDocument(CreateLegalDocumentRequest request) {
        if (legalDocumentRepository.existsBySlug(request.slug())) {
            throw new AppException(CommonErrorCode.RESOURCE_CONFLICT, "Slug already exists: " + request.slug());
        }

        LegalDocument document = LegalDocument.builder()
                .slug(request.slug().trim().toLowerCase())
                .docType(request.docType())
                .targetAudience(request.resolveAudience())
                .title(request.title().trim())
                .eyebrow(request.eyebrow())
                .summary(request.summary())
                .content(request.content())
                .version(request.version().trim())
                .locale(request.resolveLocale())
                .keywords(LegalDocumentKeywords.clean(request.keywords()))
                .active(request.resolveActive())
                .effectiveFrom(request.resolveEffectiveFrom())
                .build();

        LegalDocument saved = legalDocumentRepository.save(document);
        log.info("Created legal document: id={}, slug={}", saved.getId(), saved.getSlug());
        return LegalDocumentDetailResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public LegalDocumentDetailResponse updateDocument(UUID id, UpdateLegalDocumentRequest request) {
        LegalDocument doc = legalDocumentRepository.findById(id)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Document not found with id: " + id));

        if (request.title() != null && !request.title().trim().isEmpty()) {
            doc.setTitle(request.title().trim());
        }
        if (request.eyebrow() != null) {
            doc.setEyebrow(request.eyebrow());
        }
        if (request.summary() != null) {
            doc.setSummary(request.summary());
        }
        if (request.content() != null && !request.content().trim().isEmpty()) {
            doc.setContent(request.content());
        }
        if (request.version() != null && !request.version().trim().isEmpty()) {
            doc.setVersion(request.version().trim());
        }
        if (request.targetAudience() != null) {
            doc.setTargetAudience(request.targetAudience());
        }
        if (request.keywords() != null) {
            doc.setKeywords(LegalDocumentKeywords.clean(request.keywords()));
        }
        if (request.active() != null) {
            doc.setActive(request.active());
        }
        if (request.effectiveFrom() != null) {
            doc.setEffectiveFrom(request.effectiveFrom());
        }

        LegalDocument updated = legalDocumentRepository.save(doc);
        log.info("Updated legal document: id={}, slug={}", updated.getId(), updated.getSlug());
        return LegalDocumentDetailResponse.fromEntity(updated);
    }

    private static boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }

    @Override
    @Transactional
    public void deleteDocument(UUID id) {
        LegalDocument doc = legalDocumentRepository.findById(id)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Document not found with id: " + id));
        legalDocumentRepository.delete(doc);
        log.info("Soft-deleted legal document: id={}, slug={}", id, doc.getSlug());
    }
}
