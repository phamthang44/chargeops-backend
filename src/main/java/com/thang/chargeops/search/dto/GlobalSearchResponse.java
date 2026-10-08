package com.thang.chargeops.search.dto;

import java.util.List;

/**
 * Payload của GET /api/v1/search. Chỉ gồm các group mà vai trò của người gọi
 * có quyền thấy — group không có quyền hoặc không có kết quả thì bị lược bỏ.
 */
public record GlobalSearchResponse(String query, List<SearchGroup> groups) {
}
