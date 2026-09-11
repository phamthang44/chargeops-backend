package com.thang.chargeops.booking.entity;

import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingEntityTest {

    private UserProfile mockDriver;
    private Connector mockConnector;
    private Instant now;
    private Instant startAt;
    private Instant endAt;
    private BigDecimal totalAmount;
    private Instant expiresAt;
    private BookingPolicySnapshot policySnapshot;

    @BeforeEach
    void setUp() {
        mockDriver = org.mockito.Mockito.mock(UserProfile.class);
        mockConnector = org.mockito.Mockito.mock(Connector.class);
        now = Instant.parse("2026-09-10T10:00:00Z");
        startAt = Instant.parse("2026-09-10T11:00:00Z");
        endAt = Instant.parse("2026-09-10T12:00:00Z"); // 60 minutes session
        totalAmount = BigDecimal.valueOf(126000);
        expiresAt = now.plus(Duration.ofMinutes(10)); // 10 minutes hold

        policySnapshot = new BookingPolicySnapshot(
                "booking-v4.9",
                "Asia/Ho_Chi_Minh",
                60,
                List.of(0, 1),
                30,
                30,
                30,
                10,
                10,
                15,
                0,
                100,
                0,
                0
        );
    }

    private Booking createValidPendingBooking() {
        return Booking.createPending(pendingSpec(endAt, totalAmount, expiresAt));
    }

    private Booking.PendingBookingSpec pendingSpec(
            Instant requestedEndAt,
            BigDecimal requestedAmount,
            Instant requestedExpiresAt
    ) {
        return Booking.PendingBookingSpec.builder()
                .driver(mockDriver)
                .connector(mockConnector)
                .startAt(startAt)
                .endAt(requestedEndAt)
                .totalAmount(requestedAmount)
                .expiresAt(requestedExpiresAt)
                .bookingCode("BK-TEST-001")
                .policyVersion("booking-v4.9")
                .policySnapshot(policySnapshot)
                .stationNameSnapshot("Trạm Sạc Vincom")
                .stationAddressSnapshot("72 Lê Thánh Tôn")
                .chargePointCodeSnapshot("CP-01")
                .connectorCodeSnapshot("CONN-01")
                .build();
    }

    @Nested
    @DisplayName("Factory Method createPending Tests (BR-BOK-02, BR-BOK-04)")
    class FactoryTests {

        @Test
        @DisplayName("createPending khởi tạo đúng trạng thái PENDING và tự tính checkInDeadline = endAt - 15m")
        void createPending_valid_initializesCorrectly() {
            Booking booking = createValidPendingBooking();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(booking.getDriver()).isSameAs(mockDriver);
            assertThat(booking.getConnector()).isSameAs(mockConnector);
            assertThat(booking.getStartAt()).isEqualTo(startAt);
            assertThat(booking.getEndAt()).isEqualTo(endAt);
            assertThat(booking.getTotalAmount()).isEqualByComparingTo(totalAmount);
            assertThat(booking.getExpiresAt()).isEqualTo(expiresAt);
            assertThat(booking.getBookingCode()).isEqualTo("BK-TEST-001");
            assertThat(booking.getPolicyVersion()).isEqualTo("booking-v4.9");
            assertThat(booking.getPolicySnapshot()).isNotNull();
            assertThat(booking.getPolicySnapshot().cancellationGraceMin()).isEqualTo(10);
            assertThat(booking.getVersion()).isEqualTo(0L);
            assertThat(booking.getPriceLines()).isNotNull().isEmpty();

            // Auto calculated deadline: 12:00:00 - 15 minutes = 11:45:00
            Instant expectedDeadline = endAt.minus(Duration.ofMinutes(15));
            assertThat(booking.getCheckInDeadline()).isEqualTo(expectedDeadline);
        }

        @Test
        @DisplayName("createPending bắt buộc expiresAt không được null (BR-BOK-02)")
        void createPending_nullExpiresAt_throwsException() {
            assertThatThrownBy(() -> Booking.createPending(pendingSpec(endAt, totalAmount, null)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("expiresAt must not be null");
        }

        @Test
        @DisplayName("createPending từ chối nếu endAt <= startAt")
        void createPending_invalidTimeRange_throwsException() {
            assertThatThrownBy(() -> Booking.createPending(pendingSpec(startAt, totalAmount, expiresAt)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endAt must be strictly after startAt");
        }

        @Test
        @DisplayName("createPending từ chối totalAmount âm")
        void createPending_negativeAmount_throwsException() {
            assertThatThrownBy(() -> Booking.createPending(
                    pendingSpec(endAt, BigDecimal.valueOf(-1000), expiresAt)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("totalAmount must not be negative");
        }
    }

    @Nested
    @DisplayName("Guarded State Transitions Tests")
    class StateTransitionTests {

        @Test
        @DisplayName("confirmPayment chuyển PENDING sang CONFIRMED và lưu mốc thanh toán/ân hạn (BR-PAY-02)")
        void confirmPayment_fromPending_success() {
            Booking booking = createValidPendingBooking();
            Instant paidAt = now.plus(Duration.ofMinutes(3));
            Instant graceDeadline = paidAt.plus(Duration.ofMinutes(10));

            booking.confirmPayment(paidAt, graceDeadline);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getPaymentConfirmedAt()).isEqualTo(paidAt);
            assertThat(booking.getFreeCancellationDeadline()).isEqualTo(graceDeadline);
        }

        @Test
        @DisplayName("confirmPayment thất bại nếu booking không ở trạng thái PENDING")
        void confirmPayment_fromNonPending_throwsException() {
            Booking booking = createValidPendingBooking();
            booking.confirmPayment(now, now.plus(Duration.ofMinutes(10)));

            assertThatThrownBy(() -> booking.confirmPayment(now, now.plus(Duration.ofMinutes(10))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot confirm payment for booking with status: CONFIRMED");
        }

        @Test
        @DisplayName("checkIn trong cửa sổ hợp lệ chuyển sang CHECKED_IN và bảo toàn endAt, totalAmount")
        void checkIn_withinWindow_preservesInvariants() {
            Booking booking = createValidPendingBooking();
            booking.confirmPayment(now, now.plus(Duration.ofMinutes(10)));

            // Late arrival: đến lúc 11:40 (sát deadline 11:45)
            Instant lateArrival = startAt.plus(Duration.ofMinutes(40));
            booking.checkIn(lateArrival);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
            assertThat(booking.getCheckedInAt()).isEqualTo(lateArrival);

            // Bất biến: Đến muộn không thay đổi endAt hoặc totalAmount
            assertThat(booking.getEndAt()).isEqualTo(endAt);
            assertThat(booking.getTotalAmount()).isEqualByComparingTo(totalAmount);
        }

        @Test
        @DisplayName("checkIn trước startAt hoặc từ checkInDeadline trở đi bị từ chối (BR-BOK-04)")
        void checkIn_outsideHalfOpenWindow_throwsException() {
            Booking booking = createValidPendingBooking();
            booking.confirmPayment(now, now.plus(Duration.ofMinutes(10)));

            assertThatThrownBy(() -> booking.checkIn(startAt.minusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before booking start time");

            assertThatThrownBy(() -> booking.checkIn(endAt.minus(Duration.ofMinutes(15))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at or after deadline");
        }

        @Test
        @DisplayName("complete phiên sạc bảo toàn endAt và totalAmount dù hoàn thành sớm")
        void complete_early_preservesInvariants() {
            Booking booking = createValidPendingBooking();
            booking.confirmPayment(now, now.plus(Duration.ofMinutes(10)));
            booking.checkIn(startAt);
            booking.startCharging(startAt.plus(Duration.ofMinutes(2)));

            // Khách rút sạc hoàn thành lúc 11:30 (sớm 30 phút so với 12:00)
            Instant earlyComplete = startAt.plus(Duration.ofMinutes(30));
            booking.complete(earlyComplete);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(booking.getCompletedAt()).isEqualTo(earlyComplete);

            // Bất biến: hoàn thành sớm không làm giảm endAt hoặc totalAmount
            assertThat(booking.getEndAt()).isEqualTo(endAt);
            assertThat(booking.getTotalAmount()).isEqualByComparingTo(totalAmount);
        }

        @Test
        @DisplayName("cancel ghi nhận reason và chỉ cho phép từ PENDING hoặc CONFIRMED (BR-BOK-06)")
        void cancel_recordsReasonAndRejectsCheckedInBooking() {
            Booking booking = createValidPendingBooking();
            booking.confirmPayment(now, now.plus(Duration.ofMinutes(10)));

            Instant cancelTime = now.plus(Duration.ofMinutes(5));
            booking.cancel("NO_SHOW", cancelTime);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getCancellationReason()).isEqualTo("NO_SHOW");
            assertThat(booking.getCancelledAt()).isEqualTo(cancelTime);

            assertThatThrownBy(() -> booking.cancel("DRIVER", now))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("status: CANCELLED");

            Booking checkedIn = createValidPendingBooking();
            checkedIn.confirmPayment(now, now.plus(Duration.ofMinutes(10)));
            checkedIn.checkIn(startAt);

            assertThatThrownBy(() -> checkedIn.cancel("DRIVER", startAt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("status: CHECKED_IN");
        }

        @Test
        @DisplayName("expire chuyển PENDING sang EXPIRED khi hết 10 phút thanh toán (BR-BOK-02)")
        void expire_fromPending_success() {
            Booking booking = createValidPendingBooking();

            booking.expire();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("BookingPriceLine Integration Tests")
    class PriceLinesTests {

        @Test
        @DisplayName("addPriceLine duy trì quan hệ hai chiều chính xác")
        void addPriceLine_maintainsBidirectionalRelationship() {
            Booking booking = createValidPendingBooking();

            BookingPriceLine line = BookingPriceLine.builder()
                    .sequence(1)
                    .segmentStart(startAt)
                    .segmentEnd(endAt)
                    .durationMinutes(60)
                    .label("Giờ bình thường")
                    .periodCode("NORMAL")
                    .rateVndPerKwh(BigDecimal.valueOf(3400))
                    .estimatedEnergyKwh(BigDecimal.valueOf(37.2))
                    .powerKw(BigDecimal.valueOf(60))
                    .energyFactor(BigDecimal.valueOf(0.62))
                    .formulaVersion("v1")
                    .amount(BigDecimal.valueOf(126000))
                    .build();

            booking.addPriceLine(line);

            assertThat(booking.getPriceLines()).containsExactly(line);
            assertThat(line.getBooking()).isSameAs(booking);
            assertThatThrownBy(() -> booking.getPriceLines().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
