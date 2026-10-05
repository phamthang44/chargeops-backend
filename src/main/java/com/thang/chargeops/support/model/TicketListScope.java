package com.thang.chargeops.support.model;

/**
 * Phân vùng dữ liệu cho danh sách ticket dùng chung (GET /api/v1/tickets).
 */
public enum TicketListScope {
    /**
     * Mặc định: ticket do chính người dùng báo cáo cộng với ticket thuộc trạm
     * người dùng sở hữu (OWNER) hoặc đang được phân công trực tiếp (STAFF).
     */
    ACTOR,

    /**
     * Không gian Driver: chỉ ticket mà người dùng hiện tại là người báo cáo
     * (reporter_id = currentProfile.id). Dùng cho mobile "Phiếu tôi đã báo".
     */
    REPORTER
}
