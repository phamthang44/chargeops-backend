package com.thang.chargeops.search;

import com.thang.chargeops.search.dto.SearchHit;

import java.util.List;

/**
 * Một provider = một domain tìm kiếm. Chịu hai việc: (1) tự scope theo vai trò,
 * (2) trả về đúng hình dáng dropdown. Service không biết gì về từng domain.
 */
public interface SearchProvider {

    GlobalSearchType type();

    /** Chỉ trả false khi vai trò này hoàn toàn không được xem domain (group biến mất lặng lẽ). */
    boolean supports(SearchRole role);

    /** Tối đa {@code limit} hit; scope PHẢI nằm trong predicate, không phải tham số. */
    List<SearchHit> search(SearchContext context, String query, int limit);
}
