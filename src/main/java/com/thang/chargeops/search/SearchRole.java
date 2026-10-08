package com.thang.chargeops.search;

/**
 * Vai trò quyết định phạm vi tìm kiếm. Suy ra từ JWT authorities trong
 * {@link GlobalSearchService} — KHÔNG bao giờ nhận từ query param.
 */
public enum SearchRole {
    ADMIN,
    OWNER,
    STAFF
}
