package com.thang.chargeops.search.provider;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchLike;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Người dùng (displayName / email). ADMIN only — owner/staff KHÔNG được dò
 * profile platform qua global search (nhân viên trạm không phải domain search
 * của owner đã chốt ở plan).
 */
@Component
@RequiredArgsConstructor
public class UserSearchProvider implements SearchProvider {

    private final UserProfileRepository userProfileRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.USER;
    }

    @Override
    public boolean supports(SearchRole role) {
        return role == SearchRole.ADMIN;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        return userProfileRepository.searchProfiles(SearchLike.pattern(query), PageRequest.of(0, limit))
                .stream()
                .map(user -> new SearchHit(
                        user.getId(),
                        user.getDisplayName() != null && !user.getDisplayName().isBlank()
                                ? user.getDisplayName()
                                : user.getEmail(),
                        user.getEmail(),
                        null
                ))
                .collect(Collectors.toList());
    }
}
