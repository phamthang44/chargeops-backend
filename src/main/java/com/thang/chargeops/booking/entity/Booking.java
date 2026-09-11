package com.thang.chargeops.booking.entity;

import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Đại diện cho một phiên đặt chỗ sạc điện (Booking).
 * Tuân thủ quy tắc nghiệp vụ Booking v4.9:
 * - BR-BOK-02: Giữ chỗ PENDING tối đa 10 phút, bắt buộc có expiresAt.
 * - BR-BOK-04: Cửa sổ check-in từ startAt đến trước (endAt - 15 phút). Đến muộn không kéo dài phiên.
 * - BR-BOK-05: Hết hạn check-in bị hủy CANCELLED với cancellationReason = NO_SHOW.
 * - BR-BOK-12: Lưu snapshot chính sách và chi tiết giá bất biến.
 * - BR-PAY-02: Ân hạn hủy 10 phút tính từ khi xác nhận thanh toán (paymentConfirmedAt).
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_driver_id", columnList = "driver_id"),
        @Index(name = "idx_bookings_connector_id", columnList = "connector_id"),
        @Index(name = "idx_bookings_status", columnList = "status"),
        @Index(name = "idx_bookings_connector_range", columnList = "connector_id, start_at, end_at"),
        @Index(name = "idx_bookings_driver_start_status", columnList = "driver_id, start_at, status")
})
public class Booking extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private UserProfile driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false)
    private Connector connector;

    @Column(name = "booking_code", length = 32)
    private String bookingCode;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "station_name_snapshot", nullable = false)
    private String stationNameSnapshot;

    @Column(name = "station_address_snapshot", nullable = false)
    private String stationAddressSnapshot;

    @Column(name = "charge_point_code_snapshot", nullable = false)
    private String chargePointCodeSnapshot;

    @Column(name = "connector_code_snapshot", nullable = false)
    private String connectorCodeSnapshot;

    @Column(name = "policy_version", length = 100)
    private String policyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot")
    private BookingPolicySnapshot policySnapshot;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "payment_confirmed_at")
    private Instant paymentConfirmedAt;

    @Column(name = "free_cancellation_deadline")
    private Instant freeCancellationDeadline;

    @Column(name = "check_in_deadline")
    private Instant checkInDeadline;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "charging_started_at")
    private Instant chargingStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 50)
    private String cancellationReason;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingPriceLine> priceLines = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────────
    // Factory Method (BKG-009)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Khởi tạo Booking ở trạng thái PENDING với các ràng buộc nghiệp vụ bất biến:
     * - expiresAt bắt buộc không được null (BR-BOK-02).
     * - endAt > startAt và totalAmount >= 0.
     * - Tự động tính checkInDeadline = endAt - 15 phút nếu chưa chỉ định (BR-BOK-04).
     */
    public static Booking createPending(PendingBookingSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(spec.driver(), "Driver must not be null");
        Objects.requireNonNull(spec.connector(), "Connector must not be null");
        Objects.requireNonNull(spec.startAt(), "startAt must not be null");
        Objects.requireNonNull(spec.endAt(), "endAt must not be null");
        if (!spec.endAt().isAfter(spec.startAt())) {
            throw new IllegalArgumentException("endAt must be strictly after startAt");
        }
        Objects.requireNonNull(spec.totalAmount(), "totalAmount must not be null");
        if (spec.totalAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("totalAmount must not be negative");
        }
        Objects.requireNonNull(spec.expiresAt(), "expiresAt must not be null for PENDING booking (BR-BOK-02)");

        Instant checkInDeadline = spec.checkInDeadline() != null
                ? spec.checkInDeadline()
                : spec.endAt().minus(Duration.ofMinutes(15));

        Booking booking = new Booking();
        booking.driver = spec.driver();
        booking.connector = spec.connector();
        booking.startAt = spec.startAt();
        booking.endAt = spec.endAt();
        booking.status = BookingStatus.PENDING;
        booking.totalAmount = spec.totalAmount();
        booking.expiresAt = spec.expiresAt();
        booking.bookingCode = spec.bookingCode();
        booking.policyVersion = spec.policyVersion();
        booking.policySnapshot = spec.policySnapshot();
        booking.stationNameSnapshot = spec.stationNameSnapshot();
        booking.stationAddressSnapshot = spec.stationAddressSnapshot();
        booking.chargePointCodeSnapshot = spec.chargePointCodeSnapshot();
        booking.connectorCodeSnapshot = spec.connectorCodeSnapshot();
        booking.checkInDeadline = checkInDeadline;
        booking.version = 0L;
        return booking;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Guarded Domain State Transitions (BKG-009)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Xác nhận thanh toán thành công: PENDING -> CONFIRMED.
     * Ghi nhận paymentConfirmedAt và mốc ân hạn freeCancellationDeadline (BR-PAY-02).
     */
    public void confirmPayment(Instant confirmedAt, Instant freeCancellationDeadline) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm payment for booking with status: " + this.status);
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        Objects.requireNonNull(freeCancellationDeadline, "freeCancellationDeadline must not be null");
        this.status = BookingStatus.CONFIRMED;
        this.paymentConfirmedAt = confirmedAt;
        this.freeCancellationDeadline = freeCancellationDeadline;
    }

    /**
     * Check-in phiên sạc: CONFIRMED -> CHECKED_IN (BR-BOK-04).
     * Chặn check-in nếu đã quá hạn checkInDeadline.
     * Đến muộn trong cửa sổ hợp lệ không thay đổi endAt hoặc totalAmount.
     */
    public void checkIn(Instant checkedInAt) {
        if (this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot check-in booking with status: " + this.status);
        }
        Objects.requireNonNull(checkedInAt, "checkedInAt must not be null");
        if (checkedInAt.isBefore(this.startAt)) {
            throw new IllegalStateException("Check-in rejected before booking start time: " + this.startAt);
        }
        if (this.checkInDeadline != null && !checkedInAt.isBefore(this.checkInDeadline)) {
            throw new IllegalStateException("Check-in rejected at or after deadline: " + this.checkInDeadline);
        }
        this.status = BookingStatus.CHECKED_IN;
        this.checkedInAt = checkedInAt;
    }

    /**
     * Bắt đầu sạc vật lý/mô phỏng: CHECKED_IN -> CHARGING.
     */
    public void startCharging(Instant chargingStartedAt) {
        if (this.status != BookingStatus.CHECKED_IN) {
            throw new IllegalStateException("Cannot start charging for booking with status: " + this.status);
        }
        Objects.requireNonNull(chargingStartedAt, "chargingStartedAt must not be null");
        this.status = BookingStatus.CHARGING;
        this.chargingStartedAt = chargingStartedAt;
    }

    /**
     * Hoàn thành phiên sạc: CHARGING/CHECKED_IN -> COMPLETED.
     * Hoàn thành sớm hoặc đúng giờ tuyệt đối không làm thay đổi endAt hoặc totalAmount của gói.
     */
    public void complete(Instant completedAt) {
        if (this.status != BookingStatus.CHARGING && this.status != BookingStatus.CHECKED_IN) {
            throw new IllegalStateException("Cannot complete booking with status: " + this.status);
        }
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.status = BookingStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    /**
     * Hủy đơn đặt chỗ: PENDING/CONFIRMED -> CANCELLED.
     * Ghi nhận lý do hủy và thời điểm hủy.
     */
    public void cancel(String reason, Instant cancelledAt) {
        if (this.status != BookingStatus.PENDING && this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel booking with status: " + this.status);
        }
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        this.status = BookingStatus.CANCELLED;
        this.cancellationReason = reason;
        this.cancelledAt = cancelledAt;
    }

    /**
     * Hết hạn giữ chỗ thanh toán 10 phút: PENDING -> EXPIRED (BR-BOK-02).
     */
    public void expire() {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot expire booking with status: " + this.status);
        }
        this.status = BookingStatus.EXPIRED;
    }

    /**
     * Gắn chi tiết phân đoạn giá (BookingPriceLine) vào booking.
     */
    public void addPriceLine(BookingPriceLine priceLine) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Price lines can only be added while booking is PENDING");
        }
        Objects.requireNonNull(priceLine, "priceLine must not be null");
        this.priceLines.add(priceLine);
        priceLine.attachTo(this);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Convenience / Compatibility Aliases
    // ─────────────────────────────────────────────────────────────────────────────

    public List<BookingPriceLine> getPriceLines() {
        return Collections.unmodifiableList(priceLines);
    }

    @Builder
    public record PendingBookingSpec(
            UserProfile driver,
            Connector connector,
            Instant startAt,
            Instant endAt,
            BigDecimal totalAmount,
            Instant expiresAt,
            String bookingCode,
            String policyVersion,
            BookingPolicySnapshot policySnapshot,
            String stationNameSnapshot,
            String stationAddressSnapshot,
            String chargePointCodeSnapshot,
            String connectorCodeSnapshot,
            Instant checkInDeadline
    ) {
    }
}
