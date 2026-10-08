package com.thang.chargeops.payout;

import com.thang.chargeops.payout.repository.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true", "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = NONE)
@Import({LegacyPayoutUpgradeJpaTest.Upgrade.class, OwnerBookingFinancialRepository.class})
class LegacyPayoutUpgradeJpaTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired OwnerPayoutRepository payouts;
    @Autowired OwnerPayoutItemRepository items;
    @Autowired PayoutAttemptRepository attempts;
    @Autowired OwnerAdjustmentRepository adjustments;
    @Autowired OwnerBookingFinancialRepository financials;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.thang.chargeops.payment.repository.PaymentRepository paymentRepository;
    @Autowired com.thang.chargeops.payment.repository.PaymentTransactionRepository receiptRepository;
    @Autowired com.thang.chargeops.refund.repository.RefundRepository refundRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    void bkg065CurrentOwnerLedgerRecomputesFromPreservedSourcesAfterUpgrade() {
        var profile = org.mockito.Mockito.mock(com.thang.chargeops.profile.support.CurrentProfileProvider.class);
        var finance = new com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl(
                profile, paymentRepository, receiptRepository, refundRepository);
        var financialOwner = jdbc.queryForObject("SELECT id FROM user_profile WHERE keycloak_id='payout-schema'", java.util.UUID.class);
        var nextOwner = jdbc.queryForObject("SELECT id FROM user_profile WHERE keycloak_id='legacy-next-owner'", java.util.UUID.class);
        var bookingId = jdbc.queryForObject("SELECT id FROM bookings WHERE booking_code='BKG-PAYOUT-SCHEMA'", java.util.UUID.class);
        org.mockito.Mockito.when(profile.requireProfileId()).thenReturn(financialOwner);
        var beforeClear = finance.summary();
        assertThat(beforeClear.grossVnd()).isEqualTo(100000);
        assertThat(beforeClear.refundedVnd()).isZero();
        assertThat(beforeClear.pendingRefundVnd()).isZero();
        assertThat(beforeClear.netVnd()).isEqualTo(100000);
        var beforeDetail = finance.get(bookingId);
        assertThat(beforeDetail.receipts()).hasSize(1);
        assertThat(beforeDetail.collectedAmount()).isEqualTo(100000);
        entityManager.clear();
        assertThat(finance.summary()).isEqualTo(beforeClear);
        assertThat(finance.get(bookingId)).isEqualTo(beforeDetail);
        assertThat(finance.list(1, 20).getContent()).singleElement().satisfies(row -> {
            assertThat(row.bookingId()).isEqualTo(bookingId);
            assertThat(row.collectedAmount()).isEqualTo(beforeDetail.collectedAmount());
            assertThat(row.refundedAmount()).isEqualTo(beforeDetail.refundedAmount());
            assertThat(row.pendingRefundAmount()).isEqualTo(beforeDetail.pendingRefundAmount());
            assertThat(row.netRecordedAmount()).isEqualTo(beforeDetail.netRecordedAmount());
        });
        org.mockito.Mockito.when(profile.requireProfileId()).thenReturn(nextOwner);
        assertThat(finance.summary().grossVnd()).isZero();
        assertThat(finance.list(1, 20)).isEmpty();
        assertThatThrownBy(() -> finance.get(bookingId))
                .isInstanceOfSatisfying(com.thang.chargeops.exception.AppException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.thang.chargeops.exception.errorcode.CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void readsHistoricalPayoutAfterUpgradeWithoutChangingMoneyOrFinancialOwner() {
        var payout = payouts.findAll().getFirst();
        assertThat(payout.getAmount()).isEqualByComparingTo("100000");
        assertThat(items.findAll()).singleElement().satisfies(item -> {
            assertThat(item.getAmount()).isEqualByComparingTo("100000");
            assertThat(jdbc.queryForObject("SELECT financial_owner_id FROM bookings WHERE id = ?",
                    java.util.UUID.class, item.getBooking().getId())).isEqualTo(payout.getOwner().getId());
            assertThat(item.getBooking().getConnector().getChargePoint().getStation().getOwner().getId())
                    .isNotEqualTo(payout.getOwner().getId());
            var row = financials.findByBookingId(item.getBooking().getId()).orElseThrow();
            assertThat(row.payoutPendingAmount()).isEqualByComparingTo("100000");
            assertThat(row.appliedAmount()).isEqualByComparingTo("100000");
            assertThat(row.grossCollectedAmount()).isEqualByComparingTo("120000");
        });
        assertThat(attempts.findAll()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getAmount()).isEqualByComparingTo("100000"));
        assertThat(adjustments.findAll()).singleElement()
                .satisfies(adjustment -> assertThat(adjustment.getAmount()).isEqualByComparingTo("-5000"));
    }

    @Test
    void capturesReadOnlyInventoryForThisUpgradeEnvironment() throws Exception {
        // A reproducible evidence report; it is never evidence for other databases.
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/sql/inventory-legacy-payout.sql"));
        StringBuilder report = new StringBuilder("environment=Testcontainers V45-to-current upgrade\n");
        try (var db = POSTGRES.createConnection(""); var statement = db.createStatement()) {
            boolean result = statement.execute(sql);
            while (true) {
                if (result) {
                    try (var rows = statement.getResultSet()) {
                        var metadata = rows.getMetaData();
                        for (int col = 1; col <= metadata.getColumnCount(); col++)
                            report.append(metadata.getColumnLabel(col)).append('\t');
                        report.append('\n');
                        while (rows.next()) {
                            for (int col = 1; col <= metadata.getColumnCount(); col++)
                                report.append(rows.getString(col)).append('\t');
                            report.append('\n');
                        }
                    }
                } else if (statement.getUpdateCount() == -1) break;
                result = statement.getMoreResults();
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/bkg061-upgrade-inventory.tsv"), report);
        assertThat(report.toString()).contains("owner_payouts", "owner_payout_items", "payout_attempts",
                "owner_adjustments", "v_owner_booking_financials", "trg_owner_payout_history");
    }

    @TestConfiguration
    static class Upgrade {
        @Bean
        FlywayMigrationStrategy legacyUpgrade() {
            return flyway -> {
                var dataSource = flyway.getConfiguration().getDataSource();
                Flyway.configure().dataSource(dataSource).target("45").load().migrate();
                try (var db = dataSource.getConnection();
                     var fixture = LegacyPayoutUpgradeJpaTest.class.getResourceAsStream("/payout/legacy-history.sql")) {
                    assertThat(fixture).isNotNull();
                    db.setAutoCommit(false);
                    try (var statement = db.createStatement()) {
                        statement.execute(new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    db.commit();
                    db.setAutoCommit(true);
                    var before = moneySnapshot(db);
                    flyway.migrate();
                    flyway.validate();
                    assertThat(flyway.migrate().migrationsExecuted).isZero();
                    assertThat(moneySnapshot(db)).isEqualTo(before);
                } catch (Exception e) {
                    throw new IllegalStateException("Legacy history upgrade failed", e);
                }
            };
        }

        private static java.util.List<String> moneySnapshot(java.sql.Connection db) throws Exception {
            var snapshot = new java.util.ArrayList<String>();
            for (String table : java.util.List.of("owner_payouts", "owner_payout_items", "payout_attempts",
                    "owner_adjustments", "payments", "payment_transactions", "refunds")) {
                try (var statement = db.createStatement();
                     var rows = statement.executeQuery("SELECT count(*), coalesce(sum(amount),0) FROM " + table)) {
                    rows.next();
                    snapshot.add(table + ":" + rows.getLong(1) + ":" + rows.getBigDecimal(2).toPlainString());
                }
            }
            return snapshot;
        }
    }
}
