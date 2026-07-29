package com.thang.chargeops.common.constant;

public class LogConstant {
    private LogConstant() {}

    // Các tiền tố để phân loại log (Prefixes)
    public static final String ACTION_START = "START_ACTION";
    public static final String ACTION_SUCCESS = "SUCCESS_ACTION";
    public static final String ACTION_FAILED = "FAILED_ACTION";

    // Các mã lỗi phân loại (Error Categories)
    public static final String BIZ_ERROR = "BUSINESS_LOGIC_ERROR";
    public static final String SYS_ERROR = "SYSTEM_CRITICAL_ERROR";

    // Format chung (Template) - Chỉ nên giữ các placeholder {} của SLF4J
    public static final String SERVICE_LOG_FORMAT = "[{}] - Method: {} - User: {} - Payload: {}";
}