package com.thang.chargeops.booking.config;

import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.common.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cung cấp cấu hình chính sách đặt chỗ (Booking Policy) được định kiểu chặt chẽ từ system_configs.
 * Tuân thủ nguyên tắc Fail-Fast: kiểm tra tính hợp lệ nghiệp vụ trên từng tham số và báo lỗi ngay
 * nếu dữ liệu cấu hình bị thiếu hoặc hỏng.
 */
@Component
@RequiredArgsConstructor
@Transactional(
        readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class BookingPolicyConfig {

    public static final String KEY_CANCELLATION_GRACE = "booking.cancellation_grace_minutes";
    public static final String KEY_PAYMENT_HOLD = "booking.payment_hold_minutes";
    public static final String KEY_MINIMUM_ADVANCE = "booking.minimum_advance_minutes";
    public static final String KEY_OPERATING_GRID = "booking.operating_grid_minutes";
    public static final String KEY_ADVANCE_BOOKING_DAYS = "booking.advance_booking_days";
    public static final String KEY_CHECKIN_CUTOFF_BEFORE_END = "booking.checkin_cutoff_before_end_minutes";
    public static final String KEY_DURATION_MIN = "booking.duration_min_minutes";
    public static final String KEY_DURATION_STEP = "booking.duration_step_minutes";
    public static final String KEY_DURATION_MAX = "booking.duration_max_minutes";
    public static final String KEY_MAX_PENDING_PER_DRIVER = "booking.max_pending_per_driver";

    public static final String POLICY_VERSION = "booking-v4.9";
    public static final String TIMEZONE = "Asia/Ho_Chi_Minh";

    private final SystemConfigService configService;

    public int getCancellationGraceMinutes() {
        return configService.getRequiredInt(KEY_CANCELLATION_GRACE, null, "");
    }

    public int getPaymentHoldMinutes() {
        return configService.getRequiredInt(KEY_PAYMENT_HOLD, null, "");
    }

    public int getMinimumAdvanceMinutes() {
        return configService.getRequiredInt(KEY_MINIMUM_ADVANCE, null, "");
    }

    public int getOperatingGridMinutes() {
        return configService.getRequiredInt(KEY_OPERATING_GRID, null, "");
    }

    public int getAdvanceBookingDays() {
        return configService.getRequiredInt(KEY_ADVANCE_BOOKING_DAYS, null, "");
    }

    public int getCheckInCutoffBeforeEndMinutes() {
        return configService.getRequiredInt(KEY_CHECKIN_CUTOFF_BEFORE_END, null, "");
    }

    public int getMinDurationMinutes() {
        return configService.getRequiredInt(KEY_DURATION_MIN, null, "");
    }

    public int getDurationStepMinutes() {
        return configService.getRequiredInt(KEY_DURATION_STEP, null, "");
    }

    public int getMaxDurationMinutes() {
        return configService.getRequiredInt(KEY_DURATION_MAX, null, "");
    }

    public int getMaxPendingPerDriver() {
        return configService.getRequiredInt(KEY_MAX_PENDING_PER_DRIVER, null, "");
    }

    /**
     * Sinh CancellationPolicySummary động từ cấu hình hệ thống hiện hành cho Discovery.
     */
    public CancellationPolicySummary getCancellationSummary() {
        BookingPolicyResponse policy = toPolicyResponse();
        return new CancellationPolicySummary(
                policy.policyVersion(),
                policy.cancellationGraceMin(),
                "PAYMENT_CONFIRMED_AT",
                true,
                true,
                100,
                0,
                0,
                100,
                true
        );
    }

    /**
     * Sinh BookingPolicyResponse DTO khớp contract GET /booking-policy.
     */
    public BookingPolicyResponse toPolicyResponse() {
        int advanceDays = getAdvanceBookingDays();
        List<Integer> dayOffsets = new ArrayList<>();
        for (int i = 0; i < advanceDays; i++) {
            dayOffsets.add(i);
        }

        int grace = getCancellationGraceMinutes();
        String summary = String.format(
                "Giá gói cố định. Hủy hoàn 100%% trong %d phút từ xác nhận thanh toán trước giờ bắt đầu; sau đó 0%%. Trạm lỗi được xác nhận hoàn 100%% trong MVP.",
                grace
        );

        int grid = getOperatingGridMinutes();
        int lead = getMinimumAdvanceMinutes();
        int hold = getPaymentHoldMinutes();
        int cutoff = getCheckInCutoffBeforeEndMinutes();

        return new BookingPolicyResponse(
                versionFor(grace, hold, lead, grid, advanceDays, cutoff),
                TIMEZONE,
                lead,
                dayOffsets,
                grid,
                getMinDurationMinutes(),
                getDurationStepMinutes(),
                hold,
                grace,
                cutoff,
                0,
                100,
                0,
                0,
                List.of("SIMULATOR"),
                summary
        );
    }

    // The legacy token identifies exactly the seeded six-value policy.
    // Other configurations use a deterministic fingerprint; reverting restores the same token.
    private String versionFor(int grace, int hold, int lead, int grid, int days, int cutoff) {
        String values = grace + ":" + hold + ":" + lead + ":" + grid + ":" + days + ":" + cutoff;
        if (values.equals("10:10:60:30:2:15")) {
            return POLICY_VERSION;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((POLICY_VERSION + ":" + TIMEZONE + ":" + values).getBytes(StandardCharsets.UTF_8));
            return POLICY_VERSION + "-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    /**
     * Factory tạo instance mặc định (dành cho unit tests độc lập không cần database).
     */
    public static BookingPolicyConfig defaults() {
        return new BookingPolicyConfig(null) {
            @Override
            public int getCancellationGraceMinutes() {
                return 10;
            }

            @Override
            public int getPaymentHoldMinutes() {
                return 10;
            }

            @Override
            public int getMinimumAdvanceMinutes() {
                return 60;
            }

            @Override
            public int getOperatingGridMinutes() {
                return 30;
            }

            @Override
            public int getAdvanceBookingDays() {
                return 2;
            }

            @Override
            public int getCheckInCutoffBeforeEndMinutes() {
                return 15;
            }

            @Override
            public int getMinDurationMinutes() {
                return 30;
            }

            @Override
            public int getDurationStepMinutes() {
                return 30;
            }

            @Override
            public int getMaxDurationMinutes() {
                return 180;
            }

            @Override
            public int getMaxPendingPerDriver() {
                return 1;
            }
        };
    }
}
