package com.thang.chargeops.booking.enums;

/**
 * Mức hoàn tiền do cancellation policy quyết định.
 * Đây là khái niệm của booking, không phải enum dùng chung toàn hệ thống.
 */
public enum CancellationRefundTier {
    GRACE,
    FULL,
    PARTIAL,
    NONE
}
