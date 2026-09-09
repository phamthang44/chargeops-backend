package com.thang.chargeops.booking.dto.response;

import java.util.List;

/**
 * Payload phản hồi chính sách đặt chỗ tổng hợp theo chuẩn Booking v4.9 (Mục 8.1 API Contract).
 * Khớp hoàn toàn với schema PolicyEnvelope/Policy trong openapi.json và fixtures.json.
 */
public record BookingPolicyResponse(
        String policyVersion,
        String timezone,
        int advanceMinMinutes,
        List<Integer> selectableStartDayOffsets,
        int startStepMin,
        int minDurationMin,
        int durationStepMin,
        int paymentHoldMin,
        int cancellationGraceMin,
        int checkInCloseBeforeEndMin,
        int voluntaryRefundAfterGracePercent,
        int verifiedStationFailureRefundPercent,
        int bookingFee,
        int commissionPercent,
        List<String> supportedPaymentMethods,
        String summary
) {
}
