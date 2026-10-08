package com.thang.chargeops.search.provider;

import com.thang.chargeops.legal.domain.LegalDocument;
import com.thang.chargeops.legal.repository.LegalDocumentRepository;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import com.thang.chargeops.legal.repository.specs.LegalDocumentSpecification;
import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Văn bản pháp lý & chính sách. Text match reuse {@link LegalDocumentSpecification}
 * (unaccent + keywords, đúng "pragmatic search" trong Vault doc 08).
 * Scope: ADMIN thấy cả bản nháp ({@code active = null}); OWNER/STAFF chỉ bản
 * đang hiệu lực — dữ liệu này công khai (endpoint đọc là permitAll) nên không
 * cần filter audience.
 */
@Component
@RequiredArgsConstructor
public class LegalDocumentSearchProvider implements SearchProvider {

    private static final int SUMMARY_MAX_LENGTH = 120;

    private final LegalDocumentRepository legalDocumentRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.LEGAL_DOCUMENT;
    }

    @Override
    public boolean supports(SearchRole role) {
        return true;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        boolean activeOnly = context.role() != SearchRole.ADMIN;
        LegalDocumentFilter filter = LegalDocumentFilter.builder()
                .search(query)
                .active(activeOnly ? Boolean.TRUE : null)
                .build();

        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("updatedAt")));
        return legalDocumentRepository.findAll(LegalDocumentSpecification.filter(filter), page)
                .getContent()
                .stream()
                .map(this::toHit)
                .collect(Collectors.toList());
    }

    private SearchHit toHit(LegalDocument document) {
        return new SearchHit(
                document.getId(),
                document.getTitle(),
                truncate(document.getSummary()),
                document.getDocType() != null ? document.getDocType().name().toLowerCase(Locale.ROOT) : null
        );
    }

    private String truncate(String text) {
        if (text == null || text.length() <= SUMMARY_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, SUMMARY_MAX_LENGTH).trim() + "…";
    }
}
