package com.thang.chargeops.booking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;

import java.io.Serializable;
import java.util.List;

/**
 * Snapshot các quy tắc chính sách (Booking Policy) được chụp tại thời điểm tạo đơn đặt chỗ.
 * Dùng để lưu trữ bất biến vào cột bookings.policy_snapshot (jsonb), đảm bảo khiếu nại/tranh chấp
 * được đối soát theo đúng điều khoản khách hàng đã đồng ý khi đặt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingPolicySnapshot(
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
        int commissionPercent
) implements Serializable {

    public BookingPolicySnapshot {
        selectableStartDayOffsets = selectableStartDayOffsets == null
                ? List.of()
                : List.copyOf(selectableStartDayOffsets);
    }

    public static BookingPolicySnapshot from(BookingPolicyResponse response) {
        if (response == null) {
            return null;
        }
        return new BookingPolicySnapshot(
                response.policyVersion(),
                response.timezone(),
                response.advanceMinMinutes(),
                response.selectableStartDayOffsets() != null ? List.copyOf(response.selectableStartDayOffsets()) : List.of(),
                response.startStepMin(),
                response.minDurationMin(),
                response.durationStepMin(),
                response.paymentHoldMin(),
                response.cancellationGraceMin(),
                response.checkInCloseBeforeEndMin(),
                response.voluntaryRefundAfterGracePercent(),
                response.verifiedStationFailureRefundPercent(),
                response.bookingFee(),
                response.commissionPercent()
        );
    }
}
