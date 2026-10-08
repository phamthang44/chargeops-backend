package com.thang.chargeops.search;

/**
 * Các domain mà global search có thể trả về. Giá trị là contract với frontend
 * (group label, icon, route map đều key theo enum này) — thêm giá trị mới là
 * breaking change có chủ đích, không đổi tên value tùy tiện.
 */
public enum GlobalSearchType {
    TICKET,
    STATION,
    CHARGER,
    BOOKING,
    LICENSE,
    APPROVAL,
    LEGAL_DOCUMENT,
    USER
}
