package com.thang.chargeops.search.dto;

import java.util.UUID;

/**
 * Một kết quả tìm kiếm — chỉ chứa đúng những gì dropdown cần hiển thị.
 * Không kéo theo PII ngoài scope màn hình đích (vd. không trả email ở nhóm
 * không được phép xem email).
 *
 * @param id       định danh để frontend map route chi tiết
 * @param title    dòng chính (vd. subject phiếu, tên trạm)
 * @param subtitle dòng phụ (vd. tên trạm, địa chỉ, email admin)
 * @param badge    chip mono nhỏ (mã trạm, mã phiếu, loại văn bản) — nullable
 */
public record SearchHit(UUID id, String title, String subtitle, String badge) {
}
