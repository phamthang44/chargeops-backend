package com.thang.chargeops.search;

import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.search.dto.GlobalSearchResponse;
import com.thang.chargeops.search.dto.SearchGroup;
import com.thang.chargeops.search.dto.SearchHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aggregator cho GET /api/v1/search.
 *
 * <p>Contract: input duy nhất là keyword ({@code q}) — {@code type} (nếu có) chỉ
 * là bộ lọc thu hẹp do người dùng chủ động chọn, KHÔNG bao giờ quyết định phạm
 * vi. Scope nằm trọn trong từng {@link SearchProvider}; vai trò suy ra từ JWT
 * (ADMIN &gt; OWNER &gt; STAFF khi người dùng mang nhiều role).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalSearchService {

    public static final int MIN_QUERY_LENGTH = 2;
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;

    private final List<SearchProvider> providers;
    private final CurrentProfileProvider currentProfileProvider;

    public GlobalSearchResponse search(String rawQuery, GlobalSearchType requestedType, int limit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < MIN_QUERY_LENGTH) {
            // IllegalArgumentException → GlobalHandlerError xử lý thành 400.
            throw new IllegalArgumentException("q must be at least " + MIN_QUERY_LENGTH + " characters");
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        SearchContext context = new SearchContext(resolveRole(), currentProfileProvider.requireProfileId());
        List<SearchGroup> groups = new ArrayList<>();
        for (SearchProvider provider : providers) {
            if (requestedType != null && provider.type() != requestedType) {
                continue;
            }
            if (!provider.supports(context.role())) {
                // Không có quyền → group biến mất lặng lẽ (không 403, tránh thành oracle dò role).
                continue;
            }
            List<SearchHit> hits = provider.search(context, query, effectiveLimit);
            if (!hits.isEmpty()) {
                groups.add(new SearchGroup(provider.type(), List.copyOf(hits)));
            }
        }
        return new GlobalSearchResponse(query, List.copyOf(groups));
    }

    private SearchRole resolveRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Set<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            if (authorities.contains("ROLE_ADMIN")) {
                return SearchRole.ADMIN;
            }
            if (authorities.contains("ROLE_OWNER")) {
                return SearchRole.OWNER;
            }
            if (authorities.contains("ROLE_STAFF")) {
                return SearchRole.STAFF;
            }
        }
        // Controller đã @PreAuthorize; nhánh này chỉ phòng thủ khi service bị gọi nội bộ.
        throw new AccessDeniedException("No searchable role");
    }
}
