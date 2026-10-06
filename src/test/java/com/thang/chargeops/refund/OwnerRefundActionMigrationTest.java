package com.thang.chargeops.refund;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OwnerRefundActionMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradePreservesRefundAndAddsNoTables() throws Exception {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        config.target("45").load().migrate();
        try (var db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = db.createStatement();
             var fixture = getClass().getResourceAsStream("/payout/legacy-history.sql")) {
            sql.execute(new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            config.target("53").load().migrate();
            sql.execute("""
                    INSERT INTO refunds(booking_id,payment_id,source_payment_transaction_id,amount,currency,
                        reason,basis_type,basis_id,status,decision_at,decided_by,execution_policy,requires_admin_action)
                    SELECT p.booking_id,p.id,t.id,p.amount,'VND','STATION_FAILURE','BOOKING_CANCELLATION',
                        gen_random_uuid(),'PENDING',now(),b.financial_owner_id,'AUTO_FIRST_ATTEMPT',true
                    FROM payments p JOIN payment_transactions t ON t.payment_id=p.id JOIN bookings b ON b.id=p.booking_id
                    WHERE p.payment_code='PAYPAYOUTSCHEMA' AND t.application_classification='APPLIED'
                    """);
            String before;
            long tables;
            try (var rows = sql.executeQuery("SELECT (to_jsonb(r)-'requires_admin_action')::text FROM refunds r")) {
                assertThat(rows.next()).isTrue(); before = rows.getString(1);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")) {
                rows.next(); tables = rows.getLong(1);
            }
            var latest = config.target("54").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            latest.validate();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            try (var rows = sql.executeQuery("SELECT requires_owner_action,(to_jsonb(r)-'requires_owner_action')::text FROM refunds r")) {
                rows.next(); assertThat(rows.getBoolean(1)).isTrue(); assertThat(rows.getString(2)).isEqualTo(before);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(tables);
            }
        }
    }
}
