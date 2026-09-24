package com.thang.chargeops.booking;

import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.BookingCommandRepository;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.CheckoutResponse;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingCheckoutPersistence;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.impl.BookingServiceImpl;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.gateway.PaymentGateway;
import com.thang.chargeops.payment.gateway.PaymentGatewayRegistry;
import com.thang.chargeops.payment.gateway.PaymentGatewayUnavailableException;
import com.thang.chargeops.payment.gateway.PaymentGatewayProfile;
import com.thang.chargeops.payment.gateway.SimulatorPaymentGateway;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.docker.compose.enabled=false"
})
@Import({BookingCheckoutPersistence.class, BookingCommandRegistry.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingCheckoutIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T02:00:00Z");
    private static final Instant HOLD_EXPIRES_AT = NOW.plusSeconds(600);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private BookingCommandRegistry bookingCommandRegistry;
    @Autowired private BookingCommandRepository bookingCommandRepository;
    @Autowired private BookingCheckoutPersistence bookingCheckoutPersistence;

    private UUID driverId;
    private UUID bookingId;
    private UUID paymentId;
    private String paymentCode;
    private CurrentProfileProvider currentProfileProvider;
    private FlakySimulatorGateway gateway;
    private BookingServiceImpl service;

    @BeforeEach
    void setUp() {
        driverId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        paymentCode = "CO" + UUID.randomUUID().toString()
                .replace("-", "")
                .toUpperCase();
        insertFixture(driverId, bookingId, paymentId, paymentCode, HOLD_EXPIRES_AT);

        UserProfile driver = userProfileRepository.findById(driverId).orElseThrow();
        currentProfileProvider = mock(CurrentProfileProvider.class);
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        gateway = new FlakySimulatorGateway();
        PaymentGatewayRegistry gatewayRegistry = new PaymentGatewayRegistry(List.of(gateway));
        BookingCommandInFlightLock inFlightLock = mock(BookingCommandInFlightLock.class);
        when(inFlightLock.executeWithLock(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> action = invocation.getArgument(3);
                    return action != null ? action.get() : null;
                });

        service = new BookingServiceImpl(
                bookingRepository,
                mock(BookingPricingService.class),
                mock(BookingHoldCoordinator.class),
                currentProfileProvider,
                bookingCommandRegistry,
                mock(BookingMapper.class),
                mock(BookingPolicyConfig.class),
                paymentRepository,
                mock(com.thang.chargeops.booking.service.DriverBookingDetailAssembler.class),
                mock(DriverBookingReadPolicy.class),
                mock(BookingStatusHistoryRecorder.class),
                gatewayRegistry,
                bookingCheckoutPersistence,
                inFlightLock,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void networkFailureThenRetryKeepsOneBookingOnePaymentAndOriginalHold() {
        UUID requestKey = UUID.randomUUID();

        assertThatThrownBy(() -> service.createCheckout(bookingId, requestKey))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.CHECKOUT_UNAVAILABLE)
                );

        assertThat(rowCount("bookings", bookingId)).isEqualTo(1);
        assertThat(rowCount("payments", paymentId)).isEqualTo(1);
        assertThat(readBookingExpiry()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(readProviderOrderRef()).isNull();
        assertThat(bookingCommandRepository
                .findByActor_IdAndOperationAndRequestKey(
                        driverId,
                        BookingCommandOperation.CREATE_CHECKOUT,
                        requestKey
                )).isEmpty();

        CheckoutResponse retry = service.createCheckout(bookingId, requestKey);
        assertThat(retry.status()).isEqualTo(CheckoutStatus.READY);
        assertThat(retry.method()).isEqualTo(PaymentMethod.SIMULATOR);
        assertThat(retry.expiresAt()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(retry.checkoutReference()).isEqualTo("SIM-" + paymentCode);
        assertThat(readBookingExpiry()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(rowCount("bookings", bookingId)).isEqualTo(1);
        assertThat(rowCount("payments", paymentId)).isEqualTo(1);
        assertThat(readStatus("bookings", bookingId)).isEqualTo("PENDING");
        assertThat(readStatus("payments", paymentId)).isEqualTo("PENDING");

        CheckoutResponse replay = service.createCheckout(bookingId, requestKey);
        assertThat(replay).isEqualTo(retry);
        assertThat(gateway.calls()).isEqualTo(2);
        assertThat(bookingCommandRepository
                .findByActor_IdAndOperationAndRequestKey(
                        driverId,
                        BookingCommandOperation.CREATE_CHECKOUT,
                        requestKey
                )).isPresent();

        assertThatThrownBy(() -> service.createCheckout(
                UUID.randomUUID(),
                requestKey
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(CommandErrorCode.KEY_REUSED)
        );
    }

    @Test
    void expiredHoldIsRejectedBeforeCallingGateway() {
        jdbcTemplate.update(
                "UPDATE bookings SET expires_at = ? WHERE id = ?",
                Timestamp.from(NOW),
                bookingId
        );
        gateway.recover();

        assertThatThrownBy(() -> service.createCheckout(
                bookingId,
                UUID.randomUUID()
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BookingErrorCode.HOLD_EXPIRED)
        );
        assertThat(gateway.calls()).isZero();
        assertThat(readProviderOrderRef()).isNull();
    }

    @Test
    void anotherDriverCannotCreateCheckout() {
        UUID otherDriverId = UUID.randomUUID();
        insertUser(otherDriverId, "other-");
        when(currentProfileProvider.requireProfile())
                .thenReturn(userProfileRepository.findById(otherDriverId).orElseThrow());
        gateway.recover();

        assertThatThrownBy(() -> service.createCheckout(
                bookingId,
                UUID.randomUUID()
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BookingErrorCode.BOOKING_NOT_ACCESS)
        );
        assertThat(gateway.calls()).isZero();
        assertThat(readProviderOrderRef()).isNull();
    }

    private long rowCount(String table, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?",
                Long.class,
                id
        );
    }

    private Instant readBookingExpiry() {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM bookings WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
                bookingId
        );
    }

    private String readStatus(String table, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + table + " WHERE id = ?",
                String.class,
                id
        );
    }

    private String readProviderOrderRef() {
        return jdbcTemplate.queryForObject(
                "SELECT provider_order_ref FROM payments WHERE id = ?",
                String.class,
                paymentId
        );
    }

    private void insertFixture(
            UUID actorId,
            UUID fixtureBookingId,
            UUID fixturePaymentId,
            String fixturePaymentCode,
            Instant expiresAt
    ) {
        insertUser(actorId, "checkout-");
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        String suffix = actorId.toString();
        String shortSuffix = suffix.substring(0, 8);
        String provinceCode = "P" + shortSuffix;
        String wardCode = "W" + shortSuffix;

        jdbcTemplate.update(
                "INSERT INTO provinces(code, name, full_name) VALUES (?, ?, ?)",
                provinceCode,
                "Province " + shortSuffix,
                "Test Province " + shortSuffix
        );
        jdbcTemplate.update(
                "INSERT INTO wards(code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode,
                "Ward " + shortSuffix,
                "Test Ward " + shortSuffix,
                provinceCode
        );
        jdbcTemplate.update("""
                INSERT INTO stations(
                    id, station_code, owner_id, name, address_line, latitude, longitude,
                    contact_phone, planned_charge_point_count, status, version, ward_code,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, 'Test Address', 10.0, 106.0,
                        '0123456789', 1, 'ACTIVE', 0, ?, ?, ?)
                """,
                stationId,
                "ST-" + shortSuffix,
                actorId,
                "Checkout Station " + shortSuffix,
                wardCode,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO charge_points(
                    id, station_id, charge_point_code, max_power_kw,
                    provisioning_status, operational_status, version, created_at, updated_at
                )
                VALUES (?, ?, ?, 60.0, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """,
                chargePointId,
                stationId,
                "CP-" + suffix,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO connectors(
                    id, charge_point_id, connector_code, connector_type,
                    power_kw, charger_type, runtime_status, version, created_at, updated_at
                )
                VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE', 0, ?, ?)
                """,
                connectorId,
                chargePointId,
                "CON-" + suffix,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO bookings(
                    id, driver_id, connector_id, booking_code, start_at, end_at,
                    status, total_amount, station_name_snapshot,
                    station_address_snapshot, charge_point_code_snapshot,
                    connector_code_snapshot, expires_at, version, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 126000,
                        'Checkout Station', 'Test Address', 'CP-01', 'CON-01',
                        ?, 0, ?, ?)
                """,
                fixtureBookingId,
                actorId,
                connectorId,
                "BK-" + shortSuffix,
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.plusSeconds(7200)),
                Timestamp.from(expiresAt),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO payments(
                    id, booking_id, amount, status, method, refund_amount,
                    provider, receiving_account_ref, currency, version,
                    needs_reconciliation, payment_code, environment, created_at, updated_at
                )
                VALUES (?, ?, 126000, 'PENDING', 'SIMULATOR', 0,
                        'SIMULATOR', 'SIMULATOR', 'VND', 0, false, ?, 'SIMULATOR', ?, ?)
                """,
                fixturePaymentId,
                fixtureBookingId,
                fixturePaymentCode,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
    }

    private void insertUser(UUID id, String prefix) {
        jdbcTemplate.update("""
                INSERT INTO user_profile(id, keycloak_id, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                prefix + id,
                prefix + id + "@example.test",
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
    }

    private static final class FlakySimulatorGateway implements PaymentGateway {
        private final AtomicBoolean failNext = new AtomicBoolean(true);
        private final AtomicInteger calls = new AtomicInteger();
        private final SimulatorPaymentGateway delegate = new SimulatorPaymentGateway();

        @Override
        public boolean supports(PaymentMethod method) {
            return method == PaymentMethod.SIMULATOR;
        }

        @Override
        public PaymentGatewayProfile profile() {
            return delegate.profile();
        }

        @Override
        public OrderCheckout createCheckout(Payment payment, Instant now) {
            calls.incrementAndGet();
            if (failNext.getAndSet(false)) {
                throw new PaymentGatewayUnavailableException("simulated network failure");
            }
            return delegate.createCheckout(payment, now);
        }

        private void recover() {
            failNext.set(false);
        }

        private int calls() {
            return calls.get();
        }
    }
}
