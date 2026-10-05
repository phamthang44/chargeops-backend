package com.thang.chargeops.station.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.impl.CheckInPolicyImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckInPolicyImplTest {

    private final CheckInPolicy policy = new CheckInPolicyImpl();

    private UserProfile driver;
    private Booking booking;
    private Connector connector;
    private ChargePoint chargePoint;
    private Station station;

    private final UUID driverId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        driver = mock(UserProfile.class);
        when(driver.getId()).thenReturn(driverId);

        station = mock(Station.class);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);

        chargePoint = mock(ChargePoint.class);
        when(chargePoint.getStation()).thenReturn(station);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus()).thenReturn(OperationalChargePointStatus.AVAILABLE);

        connector = mock(Connector.class);
        when(connector.getId()).thenReturn(connectorId);
        when(connector.getConnectorCode()).thenReturn("CONN-01");
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.AVAILABLE);

        booking = mock(Booking.class);
        when(booking.getDriver()).thenReturn(driver);
        when(booking.getConnector()).thenReturn(connector);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
    }

    @Nested
    @DisplayName("Cửa sổ check-in theo BR-BOK-04 & BR-BOK-05")
    class CheckInWindowTests {

        @Test
        @DisplayName("Gói 30 phút: start 10:00, end 10:30, cutoff 10:15 — tại cutoff không check-in")
        void testPackage30Cutoff1015() {
            Instant startAt = Instant.parse("2026-09-26T10:00:00Z");
            Instant endAt = Instant.parse("2026-09-26T10:30:00Z");
            Instant cutoff = Instant.parse("2026-09-26T10:15:00Z");

            when(booking.getStartAt()).thenReturn(startAt);
            when(booking.getEndAt()).thenReturn(endAt);
            when(booking.getCheckInDeadline()).thenReturn(cutoff);

            // 1. Quá sớm: 09:59:59 -> CHECK_IN_TOO_EARLY
            Instant tooEarly = Instant.parse("2026-09-26T09:59:59Z");
            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, tooEarly))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.CHECK_IN_TOO_EARLY));

            // 2. Đúng startAt: 10:00:00 -> SUCCESS (inclusive)
            assertThatCode(() -> policy.requireCanCheckIn(driver, booking, connector, startAt))
                    .doesNotThrowAnyException();

            // 3. Trước cutoff 1 giây: 10:14:59 -> SUCCESS
            Instant beforeCutoff = Instant.parse("2026-09-26T10:14:59Z");
            assertThatCode(() -> policy.requireCanCheckIn(driver, booking, connector, beforeCutoff))
                    .doesNotThrowAnyException();

            // 4. Đúng mốc cutoff: 10:15:00 -> CHECK_IN_CLOSED (exclusive)
            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, cutoff))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.CHECK_IN_CLOSED));

            // 5. Sau cutoff: 10:15:01 -> CHECK_IN_CLOSED
            Instant afterCutoff = Instant.parse("2026-09-26T10:15:01Z");
            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, afterCutoff))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.CHECK_IN_CLOSED));
        }

        @Test
        @DisplayName("Gói 60 phút: start 10:00, end 11:00, cutoff 10:45 — tại cutoff không check-in")
        void testPackage60Cutoff1045() {
            Instant startAt = Instant.parse("2026-09-26T10:00:00Z");
            Instant endAt = Instant.parse("2026-09-26T11:00:00Z");
            Instant cutoff = Instant.parse("2026-09-26T10:45:00Z");

            when(booking.getStartAt()).thenReturn(startAt);
            when(booking.getEndAt()).thenReturn(endAt);
            when(booking.getCheckInDeadline()).thenReturn(cutoff);

            // 1. Đúng start: 10:00:00 -> SUCCESS
            assertThatCode(() -> policy.requireCanCheckIn(driver, booking, connector, startAt))
                    .doesNotThrowAnyException();

            // 2. Trước cutoff 1 giây: 10:44:59 -> SUCCESS
            Instant beforeCutoff = Instant.parse("2026-09-26T10:44:59Z");
            assertThatCode(() -> policy.requireCanCheckIn(driver, booking, connector, beforeCutoff))
                    .doesNotThrowAnyException();

            // 3. Đúng mốc cutoff: 10:45:00 -> CHECK_IN_CLOSED
            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, cutoff))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.CHECK_IN_CLOSED));
        }
    }

    @Nested
    @DisplayName("Quyền sở hữu, trạng thái booking và súng sạc")
    class PreconditionValidationTests {

        private final Instant validAt = Instant.parse("2026-09-26T10:05:00Z");

        @BeforeEach
        void setValidTime() {
            when(booking.getStartAt()).thenReturn(Instant.parse("2026-09-26T10:00:00Z"));
            when(booking.getEndAt()).thenReturn(Instant.parse("2026-09-26T10:30:00Z"));
            when(booking.getCheckInDeadline()).thenReturn(Instant.parse("2026-09-26T10:15:00Z"));
        }

        @Test
        @DisplayName("Tài xế không sở hữu booking: ném BOOKING_NOT_ACCESS")
        void rejectsDifferentDriver() {
            UserProfile otherDriver = mock(UserProfile.class);
            when(otherDriver.getId()).thenReturn(UUID.randomUUID());

            assertThatThrownBy(() -> policy.requireCanCheckIn(otherDriver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.BOOKING_NOT_ACCESS));
        }

        @Test
        @DisplayName("ID tài xế chưa được persist không được xem là cùng chủ booking")
        void rejectsNullDriverIds() {
            when(driver.getId()).thenReturn(null);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.BOOKING_NOT_ACCESS));
        }

        @Test
        @DisplayName("Booking không ở trạng thái CONFIRMED: ném STATE_CONFLICT")
        void rejectsNonConfirmedBooking() {
            when(booking.getStatus()).thenReturn(BookingStatus.PENDING);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATE_CONFLICT));
        }

        @Test
        @DisplayName("Súng sạc không khớp với booking: ném CONNECTOR_MISMATCH")
        void rejectsMismatchedConnector() {
            Connector otherConnector = mock(Connector.class);
            when(otherConnector.getId()).thenReturn(UUID.randomUUID());
            when(otherConnector.getConnectorCode()).thenReturn("CONN-OTHER");

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, otherConnector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.CONNECTOR_MISMATCH));
        }

        @Test
        @DisplayName("ID cổng sạc chưa được persist không được xem là khớp booking")
        void rejectsNullConnectorIds() {
            when(connector.getId()).thenReturn(null);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.CONNECTOR_MISMATCH));
        }

        @Test
        @DisplayName("Booking thiếu startAt: fail closed bằng STATE_CONFLICT thay vì mở cửa check-in")
        void rejectsMissingStartAt() {
            when(booking.getStartAt()).thenReturn(null);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATE_CONFLICT));
        }

        @Test
        @DisplayName("Booking thiếu cả deadline và endAt: fail closed thay vì phát sinh NullPointerException")
        void rejectsMissingCheckInBoundary() {
            when(booking.getCheckInDeadline()).thenReturn(null);
            when(booking.getEndAt()).thenReturn(null);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATE_CONFLICT));
        }

        @Test
        @DisplayName("Booking thiếu deadline snapshot: không được tính lại bằng endAt - 15 phút")
        void rejectsMissingSnapshotDeadlineEvenWhenEndAtExists() {
            when(booking.getCheckInDeadline()).thenReturn(null);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATE_CONFLICT));
        }
    }

    @Nested
    @DisplayName("Hạ tầng phần cứng và BR-STA-05 (Không re-check License)")
    class HardwareAndLicenseTests {

        private final Instant validAt = Instant.parse("2026-09-26T10:05:00Z");

        @BeforeEach
        void setValidTime() {
            when(booking.getStartAt()).thenReturn(Instant.parse("2026-09-26T10:00:00Z"));
            when(booking.getEndAt()).thenReturn(Instant.parse("2026-09-26T10:30:00Z"));
            when(booking.getCheckInDeadline()).thenReturn(Instant.parse("2026-09-26T10:15:00Z"));
        }

        @Test
        @DisplayName("BR-STA-05: License hết hạn không cấm check-in nếu dịch vụ phần cứng vẫn phục vụ")
        void allowsCheckInRegardlessOfLicenseIfHardwareServiceable() {
            // CheckInPolicyImpl hoàn toàn không gọi hay phụ thuộc LicenseRepository.
            // Miễn là hardware serviceable, check-in thành công.
            assertThatCode(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Trạm ngừng phục vụ (PAUSED): ném STATION_UNAVAILABLE")
        void rejectsWhenStationPaused() {
            when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.PAUSED);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATION_UNAVAILABLE));
        }

        @Test
        @DisplayName("Trụ sạc chưa ACTIVE provisioning: ném STATION_UNAVAILABLE")
        void rejectsWhenChargePointNotProvisioned() {
            when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.SUSPENDED);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATION_UNAVAILABLE));
        }

        @Test
        @DisplayName("Trụ sạc mất kết nối (OFFLINE): ném STATION_UNAVAILABLE")
        void rejectsWhenChargePointOffline() {
            when(chargePoint.getOperationalChargePointStatus()).thenReturn(OperationalChargePointStatus.OFFLINE);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATION_UNAVAILABLE));
        }

        @Test
        @DisplayName("Cổng sạc mất kết nối (OFFLINE): ném STATION_UNAVAILABLE")
        void rejectsWhenConnectorOffline() {
            when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.OFFLINE);

            assertThatThrownBy(() -> policy.requireCanCheckIn(driver, booking, connector, validAt))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(BookingErrorCode.STATION_UNAVAILABLE));
        }
    }
}
