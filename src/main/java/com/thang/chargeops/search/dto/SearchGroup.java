package com.thang.chargeops.search.dto;

import com.thang.chargeops.search.GlobalSearchType;

import java.util.List;

/** Một nhóm kết quả theo domain. Nhóm rỗng không bao giờ được trả về. */
public record SearchGroup(GlobalSearchType type, List<SearchHit> items) {
}
