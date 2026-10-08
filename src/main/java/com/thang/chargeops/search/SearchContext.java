package com.thang.chargeops.search;

import java.util.UUID;

/**
 * Ngữ cảnh phạm vi của một lần tìm: ai gọi (profile id nội bộ) và với vai trò nào.
 * Mỗi {@link SearchProvider} PHẢI bind {@code actorId} vào chính predicate scope
 * của nó — không được chỉ validate tham số rồi chạy query không gắn chủ thể.
 */
public record SearchContext(SearchRole role, UUID actorId) {
}
