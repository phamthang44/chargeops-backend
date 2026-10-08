package com.thang.chargeops.search;

import java.util.Locale;
import java.util.UUID;

/**
 * Helper LIKE an toàn: escape wildcard (% _ \) theo đúng convention
 * {@code ESCAPE '\'} đang dùng trong StationSpecification/LicenseSpecification,
 * và parse UUID để ô search dán id đầy đủ vẫn khớp.
 */
public final class SearchLike {

    private SearchLike() {
    }

    /** Chuyển input thành pattern {@code %...%} đã lowercase + escape. */
    public static String pattern(String raw) {
        String escaped = (raw == null ? "" : raw)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Trả về {@code null} nếu input không phải UUID hợp lệ (không ném exception). */
    public static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
