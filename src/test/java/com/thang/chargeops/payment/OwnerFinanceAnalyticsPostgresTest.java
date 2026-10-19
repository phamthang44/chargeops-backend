package com.thang.chargeops.payment;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.repository.OwnerFinanceAnalyticsRepository;
import com.thang.chargeops.payment.service.OwnerFinanceAnalyticsService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import({OwnerFinanceAnalyticsService.class, OwnerFinanceAnalyticsRepository.class,
        OwnerFinanceAnalyticsPostgresTest.Config.class})
class OwnerFinanceAnalyticsPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @TestConfiguration
    static class Config {
        @Bean Clock applicationClock() { return Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC); }
        @Bean NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbc) { return new NamedParameterJdbcTemplate(jdbc); }
    }
    @MockitoBean CurrentProfileProvider profiles;
    @Autowired OwnerFinanceAnalyticsService reports;
    @Autowired JdbcTemplate jdbc;
    private UUID owner, foreign, stationA, stationB, foreignStation;

    @BeforeEach
    void seed() {
        owner = user("analytics-owner"); foreign = user("analytics-foreign");
        stationA = station(owner, "analytics-a"); stationB = station(owner, "analytics-b");
        foreignStation = station(foreign, "analytics-foreign");
        when(profiles.requireProfileId()).thenReturn(owner);
    }

    @Test void aggregatesAllStationsAndZeroFillsVietnamDaysWithoutLeakingOtherOwners() {
        payment(stationA, "a", 100000, "2026-10-12T17:00:00Z"); // first day midnight VN
        payment(stationB, "b", 50000, "2026-10-18T17:00:00Z"); // today midnight VN
        payment(foreignStation, "foreign", 900000, "2026-10-18T17:00:00Z");
        payment(stationA, "old", 25000, "2026-10-12T16:59:59Z");
        payment(stationA, "future", 25000, "2026-10-19T11:00:00Z");
        var result = reports.analytics(null, "7d");
        assertThat(result.grossVnd()).isEqualTo(150000);
        assertThat(result.daily()).hasSize(7);
        assertThat(result.daily().getFirst().date()).hasToString("2026-10-13");
        assertThat(result.daily().getFirst().grossVnd()).isEqualTo(100000);
        assertThat(result.daily().get(1).grossVnd()).isZero();
        assertThat(result.stations()).extracting(row -> row.stationId()).containsExactly(stationA, stationB);
        assertThat(result.daily().stream().mapToLong(row -> row.netVnd()).sum()).isEqualTo(result.netVnd());
        assertThat(result.from()).isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));
        assertThat(result.to()).isEqualTo(Instant.parse("2026-10-19T17:00:00Z"));
    }

    @Test void stationFilterAndThirtyDaysAreAppliedToBothCharts() {
        payment(stationA, "a", 100000, "2026-10-01T10:00:00Z");
        payment(stationB, "b", 50000, "2026-10-18T10:00:00Z");
        var result = reports.analytics(stationA, "30d");
        assertThat(result.grossVnd()).isEqualTo(100000);
        assertThat(result.daily()).hasSize(30);
        assertThat(result.stations()).singleElement().satisfies(row -> assertThat(row.stationId()).isEqualTo(stationA));
        assertThat(reports.analytics(stationA, "7d").grossVnd()).isZero();
    }

    @Test void rejectsForeignStationAndInvalidPeriod() {
        assertThatThrownBy(() -> reports.analytics(foreignStation, "7d")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reports.analytics(null, "365d")).isInstanceOf(AppException.class);
    }

    @Test void refundsUseCompletionDayAndCanMakeNetNegative() {
        UUID payment = payment(stationA, "refund", 100000, "2026-10-01T10:00:00Z");
        refund(payment, "2026-10-18T17:00:00Z");
        var result = reports.analytics(null, "7d");
        assertThat(result.grossVnd()).isZero();
        assertThat(result.refundedVnd()).isEqualTo(100000);
        assertThat(result.netVnd()).isEqualTo(-100000);
        assertThat(result.daily().getLast().netVnd()).isEqualTo(-100000);
        assertThat(result.stations().getFirst().netVnd()).isEqualTo(-100000);
        var month = reports.analytics(null, "30d");
        assertThat(month.grossVnd()).isEqualTo(100000);
        assertThat(month.refundedVnd()).isEqualTo(100000);
        assertThat(month.netVnd()).isZero();
    }

    @Test void excludesUnappliedAndNonSimulatorReceipts() {
        UUID paid = payment(stationA, "a", 100000, "2026-10-18T10:00:00Z");
        jdbc.update("""
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,application_reason,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR','extra',999999,'VND',paid_at,'UNAPPLIED','EXTRA_PAYMENT',payment_code,0
                FROM payments WHERE id = ?
                """, paid);
        UUID external = payment(stationB, "external", 50000, "2026-10-18T10:00:00Z");
        jdbc.update("UPDATE payments SET environment='TEST' WHERE id=?", external);
        assertThat(reports.analytics(null, "7d").grossVnd()).isEqualTo(100000);
    }

    @Test void failsClosedWhenCanonicalLedgerDrifts() {
        UUID paid = payment(stationA, "drift", 100000, "2026-10-18T10:00:00Z");
        jdbc.update("UPDATE payments SET amount=90000 WHERE id=?", paid);
        assertThatThrownBy(() -> reports.analytics(null, "7d")).isInstanceOfSatisfying(AppException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED));
    }

    @Test void limitsStationRankingButKeepsTotalsAcrossAllStations() {
        for (int i = 1; i <= 12; i++) payment(station(owner, "analytics-rank-" + i), "rank" + i,
                i * 1000, "2026-10-18T10:00:00Z");
        var result = reports.analytics(null, "7d");
        assertThat(result.stationCount()).isEqualTo(12);
        assertThat(result.stations()).hasSize(10);
        assertThat(result.grossVnd()).isEqualTo(78000);
        assertThat(result.stations().getFirst().netVnd()).isEqualTo(12000);
    }

    @Test void aggregationIsNotTruncatedToFirstHundredPayments() {
        for (int i = 0; i < 105; i++) payment(stationA, "many" + i, 1000, "2026-10-18T10:00:00Z");
        var result = reports.analytics(null, "7d");
        assertThat(result.grossVnd()).isEqualTo(105000);
        assertThat(result.stations()).singleElement().satisfies(row -> assertThat(row.grossVnd()).isEqualTo(105000));
    }

    private UUID user(String key) {
        return jdbc.queryForObject("INSERT INTO user_profile(keycloak_id,email) VALUES (?,?) RETURNING id",
                UUID.class, key, key + "@example.test");
    }
    private UUID station(UUID ownerId, String name) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                VALUES (?,?,'Test','000','ACTIVE') RETURNING id
                """, UUID.class, ownerId, name);
        UUID point = jdbc.queryForObject("""
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                VALUES (?,?,'ACTIVE','AVAILABLE') RETURNING id
                """, UUID.class, id, name);
        jdbc.update("""
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                VALUES (?,?,'CCS2',60,'DC','AVAILABLE')
                """, point, name);
        return id;
    }
    private UUID payment(UUID stationId, String code, long amount, String receivedAt) {
        UUID booking = jdbc.queryForObject("""
                INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,
                    expires_at,financial_owner_id)
                SELECT s.owner_id,c.id,?,'2026-10-20 12:00Z','2026-10-20 13:00Z','COMPLETED',?,
                    s.name,'Test',cp.charge_point_code,c.connector_code,'2026-10-20 11:10Z',s.owner_id
                FROM connectors c JOIN charge_points cp ON cp.id=c.charge_point_id
                JOIN stations s ON s.id=cp.station_id WHERE s.id=? RETURNING id
                """, UUID.class, code, amount, stationId);
        UUID payment = jdbc.queryForObject("""
                INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider,
                    receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code)
                VALUES (?,?,'PAID','SIMULATOR',0,CAST(? AS timestamptz),'SIMULATOR','SIMULATOR','VND',false,'SIMULATOR',0,?)
                RETURNING id
                """, UUID.class, booking, amount, receivedAt, "ANALYTICS" + code.toUpperCase());
        jdbc.update("""
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,payment_code,version)
                VALUES (?,'SIMULATOR','SIMULATOR',?,?,'VND',CAST(? AS timestamptz),'APPLIED',?,0)
                """, payment, code, amount, receivedAt, "ANALYTICS" + code.toUpperCase());
        return payment;
    }
    private void refund(UUID payment, String completedAt) {
        UUID refund = jdbc.queryForObject("""
                INSERT INTO refunds(booking_id,payment_id,source_payment_transaction_id,amount,currency,reason,
                    basis_type,basis_id,status,decision_at,decided_by,execution_policy,requires_owner_action)
                SELECT p.booking_id,p.id,pt.id,p.amount,'VND','VOLUNTARY_GRACE','BOOKING_CANCELLATION',p.booking_id,
                    'PENDING','2026-10-18 10:00Z',b.financial_owner_id,'AUTO_FIRST_ATTEMPT',false
                FROM payments p JOIN bookings b ON b.id=p.booking_id
                JOIN payment_transactions pt ON pt.payment_id=p.id WHERE p.id=? RETURNING id
                """, UUID.class, payment);
        UUID attempt = jdbc.queryForObject("""
                INSERT INTO refund_attempts(refund_id,sequence_no,execution_mode,request_key,payload_hash,
                    idempotency_key,status,transfer_reference,started_at,completed_at,performed_at,note,execution_trigger)
                VALUES (?,1,'SIMULATOR',gen_random_uuid(),repeat('a',64),'test','SUCCEEDED','test',
                    '2026-10-18 10:00Z',CAST(? AS timestamptz),CAST(? AS timestamptz),'Test','SYSTEM_POLICY') RETURNING id
                """, UUID.class, refund, completedAt, completedAt);
        jdbc.update("UPDATE refunds SET status='SUCCEEDED',successful_attempt_id=?,completed_at=CAST(? AS timestamptz) WHERE id=?",
                attempt, completedAt, refund);
        jdbc.update("UPDATE payments SET status='REFUNDED',refund_amount=amount WHERE id=?", payment);
    }
}
