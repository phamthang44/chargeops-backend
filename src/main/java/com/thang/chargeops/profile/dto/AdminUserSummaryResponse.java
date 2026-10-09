package com.thang.chargeops.profile.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/** UM-01: summary đếm theo toàn bộ scope list, độc lập trang (UM-00 §6.1). */
@Getter
@Builder
public class AdminUserSummaryResponse {

    private Scope scope;
    private long totalProfiles;
    /** Cross-tab theo role trong scope q+status — luôn đủ 6 key, thiếu = 0. */
    private Map<String, Long> byRole;
    /** Cross-tab theo status trong scope q+role — luôn đủ ACTIVE/SUSPENDED. */
    private Map<String, Long> byStatus;
    private Instant evaluatedAt;

    @Getter
    @Builder
    public static class Scope {
        private String q;
        private String role;
        private String status;
    }
}
