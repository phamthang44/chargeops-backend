package com.thang.chargeops.refund;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.refund.service.RefundObligationServiceImpl;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(RefundObligationServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundObligationServicePostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserProfileRepository profiles;
    @Autowired ConnectorRepository connectors;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired RefundObligationService service;

    private UUID actorId;
    private UUID connectorId;
    private UUID bookingId;
    private UUID paymentId;
    private UUID receiptId;

    @BeforeEach
    void seedPaidPackage() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        String userKey = "rf-ob-" + key;
        String point = "CP-RFO-" + key;
        String connector = "C-RFO-" + key;
        String code = "RFO" + key.toUpperCase();
        jdbc.execute("""
                INSERT INTO user_profile(keycloak_id,email) VALUES ('%1$s','%1$s@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'RFO station %2$s','Test','000','ACTIVE'
                FROM user_profile WHERE keycloak_id='%1$s';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'%3$s','PROVISIONED','OPERATING'
                FROM stations WHERE name='RFO station %2$s';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'%4$s','CCS2',60,'DC','AVAILABLE'
                FROM charge_points WHERE charge_point_code='%3$s';
                INSERT INTO bookings(driver_id,connector_id,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,
                    connector_code_snapshot,expires_at)
                SELECT u.id,c.id,'2026-09-23 10:00Z','2026-09-23 11:00Z','CONFIRMED',120000,
                    'RFO station %2$s','Test','%3$s','%4$s','2026-09-23 09:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='%1$s' AND c.connector_code='%4$s';
                INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider,
                    receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code)
                SELECT id,120000,'PAID','SIMULATOR',0,now(),'SIMULATOR','SIMULATOR',
                    'VND',false,'SIMULATOR',0,'%5$s'
                FROM bookings WHERE station_name_snapshot='RFO station %2$s';
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,payment_code,va_number,version)
                SELECT id,'SIMULATOR','SIMULATOR','TX-%5$s',amount,'VND',now(),
                    'APPLIED',payment_code,'SIM-VA-%5$s',0
                FROM payments WHERE payment_code='%5$s';
                """.formatted(userKey, key, point, connector, code));
        actorId = jdbc.queryForObject("SELECT id FROM user_profile WHERE keycloak_id=?", UUID.class, userKey);
        connectorId = jdbc.queryForObject("SELECT id FROM connectors WHERE connector_code=?", UUID.class, connector);
        bookingId = jdbc.queryForObject("SELECT id FROM bookings WHERE station_name_snapshot=?", UUID.class, "RFO station " + key);
        paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE payment_code=?", UUID.class, code);
        receiptId = jdbc.queryForObject("SELECT id FROM payment_transactions WHERE transaction_ref=?", UUID.class, "TX-" + code);
    }

    @Test
    void concurrentSameSourceAndBasisCreatesOnePendingAndReplays() throws Exception {
        UUID basisId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<UUID> first = pool.submit(() -> {
                start.await();
                return create(basisId, RefundReason.VOLUNTARY_GRACE, Instant.parse("2026-09-23T08:00:00Z"));
            });
            Future<UUID> second = pool.submit(() -> {
                start.await();
                return create(basisId, RefundReason.VOLUNTARY_GRACE, Instant.parse("2026-09-23T08:00:01Z"));
            });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        Refund saved = refunds.findByBasisTypeAndBasisId(RefundBasisType.BOOKING_CANCELLATION, basisId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(saved.getAmount()).isEqualByComparingTo("120000");
        assertThat(refunds.findAll().stream().filter(r -> r.getPayment().getId().equals(paymentId))).hasSize(1);
    }

    @Test
    void conflictingBasisOrSourceDoesNotCreateSecondRefund() {
        UUID basisId = UUID.randomUUID();
        create(basisId, RefundReason.VOLUNTARY_GRACE, Instant.now());
        assertThatThrownBy(() -> create(basisId, RefundReason.STATION_FAILURE, Instant.now()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
        assertThatThrownBy(() -> create(UUID.randomUUID(), RefundReason.VOLUNTARY_GRACE, Instant.now()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
    }

    @Test
    void unappliedAndLegacyPaymentAreRejected() {
        jdbc.update("UPDATE payment_transactions SET application_classification='UNAPPLIED', application_reason='LATE' WHERE id=?", receiptId);
        assertThatThrownBy(() -> create(UUID.randomUUID(), RefundReason.VOLUNTARY_GRACE, Instant.now()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
        jdbc.update("UPDATE payment_transactions SET application_classification='APPLIED', application_reason=NULL WHERE id=?", receiptId);
        jdbc.update("UPDATE payments SET environment='LEGACY' WHERE id=?", paymentId);
        assertThatThrownBy(() -> create(UUID.randomUUID(), RefundReason.VOLUNTARY_GRACE, Instant.now()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
    }

    @Test
    void cannotCreateObligationOutsideDecisionTransaction() {
        assertThatThrownBy(() -> service.createObligation(null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void stationFailureFindingUsesSameAmountDerivation() {
        UUID basisId = UUID.randomUUID();
        UUID id = create(basisId, RefundReason.STATION_FAILURE,
                RefundBasisType.STATION_FAILURE_FINDING, Instant.now());
        Refund saved = refunds.findById(id).orElseThrow();
        assertThat(saved.getBasisId()).isEqualTo(basisId);
        assertThat(saved.getReason()).isEqualTo(RefundReason.STATION_FAILURE);
        assertThat(saved.getAmount()).isEqualByComparingTo("120000");
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void secondAppliedReceiptCannotCreateAnotherFullPackageObligationForSamePayment() {
        create(UUID.randomUUID(), RefundReason.VOLUNTARY_GRACE, Instant.now());
        String extraRef = "EXTRA-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,
                    transaction_ref,amount,currency,received_at,application_classification,
                    payment_code,va_number,version)
                SELECT id,provider,receiving_account_ref,?,amount,currency,now(),
                    'APPLIED',payment_code,va_number,0
                FROM payments WHERE id=?
                """, extraRef, paymentId);
        receiptId = jdbc.queryForObject(
                "SELECT id FROM payment_transactions WHERE transaction_ref=?", UUID.class, extraRef);
        assertThatThrownBy(() -> create(UUID.randomUUID(), RefundReason.STATION_FAILURE, Instant.now()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
        assertThat(refunds.findAll().stream().filter(r -> r.getPayment().getId().equals(paymentId))).hasSize(1);
    }

    private UUID create(UUID basisId, RefundReason reason, Instant decisionAt) {
        return create(basisId, reason, RefundBasisType.BOOKING_CANCELLATION, decisionAt);
    }

    private UUID create(UUID basisId, RefundReason reason, RefundBasisType basisType, Instant decisionAt) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            UserProfile actor = profiles.findByIdWithLock(actorId).orElseThrow();
            connectors.findByIdWithLock(connectorId).orElseThrow();
            Booking booking = bookings.findByIdWithLock(bookingId).orElseThrow();
            Payment payment = payments.findByIdWithLock(paymentId).orElseThrow();
            return service.createObligation(new CreateRefundObligationCommand(
                    booking, payment, receiptId, basisType,
                    basisId, reason, actor, decisionAt)).getId();
        });
    }
}
