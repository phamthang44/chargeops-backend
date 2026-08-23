package com.thang.chargeops.common.enums;

public enum ProvisioningStatus {
    PENDING_ACTIVATION, //-> record đã provision nhưng chưa hoạt động
    ACTIVE,    //-> được phép tham gia hệ thống
    SUSPENDED, //-> bị admin vô hiệu hóa vì policy
}
