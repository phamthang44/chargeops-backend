package com.thang.chargeops.payment;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.executor.SimulatorRefundExecutor;
import com.thang.chargeops.refund.model.*;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundDetailAssembler;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.refund.service.impl.OwnerRefundServiceImpl;
import com.thang.chargeops.station.repository.StationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import({OwnerFinanceServiceImpl.class, OwnerRefundServiceImpl.class, RefundDetailAssembler.class,
        RefundExecutorRegistry.class, SimulatorRefundExecutor.class, RefundExecutionResultHandler.class,
        OwnerFinancialScopePostgresTest.TimeConfig.class})
class OwnerFinancialScopePostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class TimeConfig {
        @Bean Clock applicationClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @MockitoBean CurrentProfileProvider profiles;
    @MockitoSpyBean SimulatorRefundExecutor simulator;
    @Autowired OwnerFinanceServiceImpl finance;
    @Autowired OwnerRefundServiceImpl ownerRefunds;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository receipts;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired UserProfileRepository users;
    @Autowired StationRepository stations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    private UUID ownerA, ownerB, bookingA, bookingB, refundA, refundB, stationA;

    @BeforeEach
    void seedTwoIndependentOwners() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email) VALUES
                ('scope-073-a','scope-073-a@example.test'), ('scope-073-b','scope-073-b@example.test');
            INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,keycloak_id,'Test','000','ACTIVE' FROM user_profile WHERE keycloak_id LIKE 'scope-073-%';
            INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,name,'ACTIVE','AVAILABLE' FROM stations WHERE name LIKE 'scope-073-%';
            INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,charge_point_code,'CCS2',60,'DC','AVAILABLE' FROM charge_points
                WHERE charge_point_code LIKE 'scope-073-%';
            INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at,
                financial_owner_id)
                SELECT s.owner_id,c.id,s.name,'2026-10-06 12:00Z','2026-10-06 13:00Z','CONFIRMED',120000,
                    s.name,'Test',cp.charge_point_code,c.connector_code,'2026-10-06 11:10Z',
                    (SELECT id FROM user_profile WHERE keycloak_id <> s.name AND keycloak_id LIKE 'scope-073-%' LIMIT 1)
                FROM connectors c JOIN charge_points cp ON cp.id=c.charge_point_id JOIN stations s ON s.id=cp.station_id
                WHERE s.name LIKE 'scope-073-%';
            INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider,
                receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code)
                SELECT id,120000,'PAID','SIMULATOR',0,now(),'SIMULATOR','SIMULATOR','VND',false,'SIMULATOR',0,
                    upper(replace(booking_code, '-', '')) FROM bookings WHERE booking_code LIKE 'scope-073-%';
            INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                amount,currency,received_at,application_classification,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR',payment_code,amount,'VND',now(),'APPLIED',payment_code,0
                FROM payments WHERE payment_code LIKE 'SCOPE073%';
            """);
        ownerA = users.findByKeycloakId("scope-073-a").orElseThrow().getId();
        ownerB = users.findByKeycloakId("scope-073-b").orElseThrow().getId();
        bookingA = payments.findByPaymentCode("SCOPE073A").orElseThrow().getBooking().getId();
        bookingB = payments.findByPaymentCode("SCOPE073B").orElseThrow().getBooking().getId();
        stationA = bookings.findById(bookingA).orElseThrow().getConnector().getChargePoint().getStation().getId();
        refundA = failedRefund("scope-073-a", ownerA);
        refundB = failedRefund("scope-073-b", ownerB);
        em.flush(); em.clear();
        actAs(ownerA);
    }

    private UUID failedRefund(String code, UUID ownerId) {
        var payment = payments.findByPaymentCode(code.replace("-", "").toUpperCase(java.util.Locale.ROOT)).orElseThrow();
        var source = receipts.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId()).getFirst();
        var actor = users.findById(ownerId).orElseThrow();
        var refund = refunds.saveAndFlush(Refund.createPending(new PendingRefundSpec(payment.getBooking(), payment,
                source, RefundReason.VOLUNTARY_GRACE, RefundBasisType.BOOKING_CANCELLATION,
                UUID.randomUUID(), NOW.minusSeconds(10), actor)));
        UUID key = UUID.randomUUID();
        var attempt = RefundAttempt.start(new PendingRefundAttemptSpec(refund, 1, RefundExecutionMode.SIMULATOR,
                key, "a".repeat(64), "refund:" + key, RefundExecutionTrigger.OWNER, actor, NOW.minusSeconds(5)));
        attempt.completeFailed("SIMULATED_FAILURE", "Fixture failure", NOW.minusSeconds(3), NOW.minusSeconds(3));
        attempts.saveAndFlush(attempt);
        refund.requireOwnerAction();
        refunds.flush();
        return refund.getId();
    }

    private void actAs(UUID ownerId) {
        when(profiles.requireProfileId()).thenReturn(ownerId);
        when(profiles.requireProfile()).thenAnswer(inv -> users.findById(ownerId).orElseThrow());
    }

    private void assertOnlyOwnLedger(UUID ownBooking, UUID foreignBooking, UUID ownRefund, UUID foreignRefund) {
        var summary = finance.summary();
        assertThat(summary.grossVnd()).isEqualTo(120000);
        assertThat(summary.refundedVnd()).isZero();
        assertThat(summary.pendingRefundVnd()).isEqualTo(120000);
        assertThat(summary.totalBookings()).isEqualTo(1);
        assertThat(finance.list(1, 10).getContent()).singleElement()
                .satisfies(row -> assertThat(row.bookingId()).isEqualTo(ownBooking));
        assertThat(finance.get(ownBooking).receipts()).hasSize(1);
        assertThatThrownBy(() -> finance.get(foreignBooking)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertThat(ownerRefunds.summary().totalPendingCount()).isEqualTo(1);
        assertThat(ownerRefunds.summary().totalFailedAttemptsCount()).isEqualTo(1);
        assertThat(ownerRefunds.summary().pendingRefundAmountVnd()).isEqualTo(120000);
        assertThat(ownerRefunds.list(null, 1, 10).getContent()).singleElement()
                .satisfies(row -> assertThat(row.refundId()).isEqualTo(ownRefund));
        assertThat(ownerRefunds.get(ownRefund).attempts()).hasSize(1);
        assertThatThrownBy(() -> ownerRefunds.get(foreignRefund)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void serverSnapshotOverridesSuppliedBeneficiaryAndAllReadsStayOwnerScoped() {
        assertThat(bookings.findById(bookingA).orElseThrow().getFinancialOwnerId()).isEqualTo(ownerA);
        assertThat(bookings.findById(bookingB).orElseThrow().getFinancialOwnerId()).isEqualTo(ownerB);
        assertOnlyOwnLedger(bookingA, bookingB, refundA, refundB);
        actAs(ownerB);
        assertOnlyOwnLedger(bookingB, bookingA, refundB, refundA);
        assertThatThrownBy(() -> ownerRefunds.retry(refundA, UUID.randomUUID(), 0)).isInstanceOf(AppException.class);
        verify(simulator, never()).execute(any());
        assertThat(attempts.countByRefundId(refundA)).isEqualTo(1);
    }

    @Test
    void pauseAndSuspensionPreservePendingObligationsAndHistoricalReads() {
        var station = stations.findById(stationA).orElseThrow();
        station.updateOperationalStatus(StationOperationalStatus.PAUSED, "Test pause");
        station.setStatus(StationStatus.SUSPENDED);
        em.flush(); em.clear();
        assertOnlyOwnLedger(bookingA, bookingB, refundA, refundB);
        assertThat(refunds.findById(refundA).orElseThrow().getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(stations.findById(stationA).orElseThrow().getDeletedAt()).isNull();
        assertThat(attempts.countByRefundId(refundA)).isEqualTo(1);
        verify(simulator, never()).execute(any());
    }

    @Test
    void matchingOwnerCanRetryWithoutChangingExistingCommandPolicy() {
        long version = refunds.findById(refundA).orElseThrow().getVersion();
        UUID key = UUID.randomUUID();
        var result = ownerRefunds.retry(refundA, key, version);
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(ownerRefunds.retry(refundA, key, version).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(attempts.countByRefundId(refundA)).isEqualTo(2);
        verify(simulator, times(1)).execute(any());
        assertThat(finance.summary().refundedVnd()).isEqualTo(120000);
        assertThat(finance.summary().pendingRefundVnd()).isZero();
        actAs(ownerB);
        assertThat(finance.summary().refundedVnd()).isZero();
    }

    @Test
    void anomalousLegacyOwnerMismatchDoesNotReassignHistoryOrGrantRetryToEitherOwner() {
        // Deliberately corrupt the fixture using SQL; this is not a supported transfer command.
        jdbc.update("UPDATE stations SET owner_id = ? WHERE id = ?", ownerB, stationA);
        em.clear();
        assertOnlyOwnLedger(bookingA, bookingB, refundA, refundB);
        assertThatThrownBy(() -> ownerRefunds.retry(refundA, UUID.randomUUID(), 0)).isInstanceOf(AppException.class);
        actAs(ownerB);
        assertOnlyOwnLedger(bookingB, bookingA, refundB, refundA);
        assertThatThrownBy(() -> ownerRefunds.retry(refundA, UUID.randomUUID(), 0)).isInstanceOf(AppException.class);
        verify(simulator, never()).execute(any());
        assertThat(attempts.countByRefundId(refundA)).isEqualTo(1);
        assertThat(bookings.findById(bookingA).orElseThrow().getFinancialOwnerId()).isEqualTo(ownerA);
    }

    @Test
    void snapshotUpdateIsRejectedByExistingDatabaseTrigger() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("UPDATE bookings SET financial_owner_id = ? WHERE id = ?")) {
                statement.setObject(1, ownerB); statement.setObject(2, bookingA);
                assertThatThrownBy(statement::executeUpdate).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("financial owner is immutable");
            } finally {
                connection.rollback(savepoint);
            }
            return null;
        });
        assertThat(bookings.findById(bookingA).orElseThrow().getFinancialOwnerId()).isEqualTo(ownerA);
    }

    @Test
    void persistedStationRejectsOwnerReassignmentWhileAllowingNormalMetadataEdits() {
        var station = stations.findById(stationA).orElseThrow();
        assertThatThrownBy(() -> station.setOwner(users.findById(ownerB).orElseThrow()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not supported");
        station.setName("Updated name");
        em.flush(); em.clear();
        assertThat(stations.findById(stationA).orElseThrow().getOwner().getId()).isEqualTo(ownerA);
        assertThat(stations.findById(stationA).orElseThrow().getName()).isEqualTo("Updated name");
    }
}
