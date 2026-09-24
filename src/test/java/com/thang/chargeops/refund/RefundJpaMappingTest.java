package com.thang.chargeops.refund;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.model.*;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
class RefundJpaMappingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired RefundRepository refundRepository;
    @Autowired RefundAttemptRepository attemptRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentTransactionRepository transactionRepository;
    @Autowired UserProfileRepository profileRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seedPaidPackage() {
        jdbcTemplate.execute("""
                INSERT INTO user_profile(keycloak_id,email)
                VALUES ('refund-jpa','refund-jpa@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Refund JPA station','Test','000','ACTIVE'
                FROM user_profile WHERE keycloak_id='refund-jpa';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-REFUND-JPA','PROVISIONED','OPERATING'
                FROM stations WHERE name='Refund JPA station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-REFUND-JPA','CCS2',60,'DC','AVAILABLE'
                FROM charge_points WHERE charge_point_code='CP-REFUND-JPA';
                INSERT INTO bookings(
                    driver_id, connector_id, start_at, end_at, status, total_amount,
                    station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at
                )
                SELECT u.id, c.id, '2026-09-23 10:00Z', '2026-09-23 11:00Z',
                       'CONFIRMED', 120000, 'Refund JPA station', 'Test',
                       'CP-REFUND-JPA', 'C-REFUND-JPA', '2026-09-23 09:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='refund-jpa' AND c.connector_code='C-REFUND-JPA';
                INSERT INTO payments(
                    booking_id, amount, status, method, refund_amount, paid_at, provider,
                    receiving_account_ref, currency, needs_reconciliation, environment,
                    version, payment_code
                )
                SELECT id, 120000, 'PAID', 'SIMULATOR', 0, now(), 'SIMULATOR',
                       'SIMULATOR', 'VND', false, 'SIMULATOR', 0, 'RFJPAPAY'
                FROM bookings WHERE station_name_snapshot='Refund JPA station';
                INSERT INTO payment_transactions(
                    payment_id, provider, receiving_account_ref, transaction_ref,
                    amount, currency, received_at, application_classification,
                    application_reason, payment_code, va_number, version
                )
                SELECT id, 'SIMULATOR', 'SIMULATOR', 'RF-JPA-TX', amount, 'VND', now(),
                       'APPLIED', NULL, payment_code, 'SIM-VA-RF-JPA', 0
                FROM payments WHERE payment_code='RFJPAPAY';
                """);
        entityManager.clear();
    }

    @Test
    void hibernateValidateRoundTripsAggregateAndIncrementsRefundVersion() {
        Booking booking = bookingRepository.findAll().stream()
                .filter(candidate -> "Refund JPA station".equals(candidate.getStationNameSnapshot()))
                .findFirst().orElseThrow();
        Payment payment = paymentRepository.findByPaymentCode("RFJPAPAY").orElseThrow();
        PaymentTransaction source = transactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", "SIMULATOR", "RF-JPA-TX")
                .orElseThrow();
        UserProfile actor = profileRepository.findByKeycloakId("refund-jpa").orElseThrow();
        Instant decisionAt = Instant.parse("2026-09-22T08:00:00Z");
        UUID basisId = UUID.randomUUID();

        Refund refund = refundRepository.saveAndFlush(Refund.createPending(new PendingRefundSpec(
                booking, payment, source, RefundReason.VOLUNTARY_GRACE,
                RefundBasisType.BOOKING_CANCELLATION, basisId, decisionAt, actor
        )));
        assertThat(refund.getVersion()).isZero();

        UUID requestKey = UUID.randomUUID();
        RefundAttempt attempt = attemptRepository.saveAndFlush(RefundAttempt.start(
                new PendingRefundAttemptSpec(
                        refund, 1, RefundExecutionMode.SIMULATOR, requestKey,
                        "b".repeat(64), "refund:" + requestKey, actor, decisionAt.plusSeconds(1)
                )
        ));
        Instant completedAt = decisionAt.plusSeconds(2);
        attempt.completeSucceeded("SIM-RF-JPA", "SIM-TRANSFER-JPA", completedAt);
        attemptRepository.saveAndFlush(attempt);
        refund.completeWith(attempt, completedAt);
        refundRepository.saveAndFlush(refund);

        UUID refundId = refund.getId();
        entityManager.clear();

        Refund reloaded = refundRepository.findById(refundId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(reloaded.getSuccessfulAttempt().getId()).isEqualTo(attempt.getId());
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(refundRepository.findBySourcePaymentTransactionId(source.getId()))
                .get().extracting(Refund::getId).isEqualTo(refundId);
        assertThat(refundRepository.findByBasisTypeAndBasisId(
                RefundBasisType.BOOKING_CANCELLATION, basisId)).isPresent();
        assertThat(attemptRepository.findByRefundIdAndRequestKey(refundId, requestKey)).isPresent();
        assertThat(attemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId))
                .extracting(RefundAttempt::getSequenceNo).containsExactly(1);
    }
}
