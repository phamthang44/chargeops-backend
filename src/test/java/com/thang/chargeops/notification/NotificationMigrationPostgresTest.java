package com.thang.chargeops.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class NotificationMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Test void upgradePreservesHistoryAndQuarantinesUnverifiableRecipientsAndKeys() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("54").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID reporter = user(jdbc), stranger = user(jdbc), ticket = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO support_tickets(id,ticket_code,category,status,reporter_id,subject,description,resolution_cycle)
                VALUES (?,'TKT-20261007-10001','ACCOUNT','RESOLVED',?,'Account support','Safe',2)
                """, ticket, reporter);
        var created = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        var read = Timestamp.from(Instant.parse("2026-10-02T00:00:00Z"));
        UUID valid = legacy(jdbc, reporter, ticket + ":1:RESOLVED", created, read);
        UUID wrongRecipient = legacy(jdbc, stranger, ticket + ":2:RESOLVED", created, null);
        UUID malformed = legacy(jdbc, reporter, "bad:not-a-number:RESOLVED", created, null);
        UUID futureCycle = legacy(jdbc, reporter, ticket + ":999:RESOLVED", created, null);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications", Long.class)).isEqualTo(4);
        var row = jdbc.queryForMap("SELECT * FROM app_notifications WHERE id=?", valid);
        assertThat(row).containsEntry("audience", "PERSONAL").containsEntry("event_type", "TICKET_RESOLVED")
                .containsEntry("ticket_id", ticket).containsEntry("title", "Historical title")
                .containsEntry("body", "Historical body").containsEntry("created_at", created).containsEntry("read_at", read)
                .containsEntry("source_event_key", ticket + ":1:RESOLVED").containsEntry("action_url", "/tickets/" + ticket);
        for (UUID id : new UUID[]{wrongRecipient, malformed, futureCycle}) {
            assertThat(jdbc.queryForMap("SELECT event_type,ticket_id FROM app_notifications WHERE id=?", id))
                    .containsEntry("event_type", "LEGACY_QUARANTINED").containsEntry("ticket_id", null);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='55' AND success", Long.class)).isEqualTo(1);
    }

    private UUID user(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id,keycloak_id,email,status) VALUES (?,?,?,'ACTIVE')", id, id.toString(), id + "@example.test");
        return id;
    }

    @Test
    void v56UpgradeKeepsPendingOutboxAndReadDismissedInboxUntouched() {
        var root = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Each upgrade scenario owns a database inside this ephemeral Testcontainers instance.
        String database = "email_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(root).execute("CREATE DATABASE " + database);
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("56").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID recipient = user(jdbc), station = UUID.randomUUID(), point = UUID.randomUUID(), connector = UUID.randomUUID(),
                booking = UUID.randomUUID(), payment = UUID.randomUUID(), receipt = UUID.randomUUID(), event = UUID.randomUUID();
        String key = receipt + ":BOOKING_CONFIRMED";
        var occurred = Instant.parse("2026-10-01T00:00:00Z");
        jdbc.update("INSERT INTO stations(id,owner_id,name,address_line,contact_phone,status,operational_status) VALUES (?,?,'Upgrade','Address','0123456789','ACTIVE','OPERATING')", station, recipient);
        jdbc.update("INSERT INTO charge_points(id,station_id,charge_point_code,provisioning_status,operational_status) VALUES (?,?,'UPGRADE-CP','ACTIVE','AVAILABLE')", point, station);
        jdbc.update("INSERT INTO connectors(id,charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status) VALUES (?,?,'UPGRADE-CON','CCS2',60,'DC','AVAILABLE')", connector, point);
        jdbc.update("""
                INSERT INTO bookings(id,driver_id,connector_id,start_at,end_at,status,total_amount,station_name_snapshot,
                    station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot)
                VALUES (?,?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '2 hours',
                    'CONFIRMED',120000,'Upgrade','Address','UPGRADE-CP','UPGRADE-CON')
                """, booking, recipient, connector);
        jdbc.update("INSERT INTO payments(id,booking_id,amount,status,method,environment,refund_amount,provider,receiving_account_ref,currency) VALUES (?,?,120000,'PAID','SIMULATOR','SIMULATOR',0,'SIMULATOR','UPGRADE-ACCOUNT','VND')", payment, booking);
        jdbc.update("INSERT INTO payment_transactions(id,payment_id,provider,receiving_account_ref,transaction_ref,amount,currency,application_classification) VALUES (?,?,'SIMULATOR','UPGRADE-ACCOUNT','UPGRADE-RECEIPT',120000,'VND','APPLIED')", receipt, payment);
        String payload = """
                {"eventId":"%s","schemaVersion":1,"eventType":"BOOKING_CONFIRMED","sourceEventKey":"%s",
                 "bookingId":"%s","stationId":"%s","recipientId":"%s","audience":"DRIVER","occurredAt":"%s"}
                """.formatted(event, key, booking, station, recipient, occurred);
        jdbc.update("""
                INSERT INTO notification_outbox(event_id,source_receipt_id,source_event_key,booking_id,station_id,
                    recipient_id,audience,event_type,schema_version,payload,occurred_at,next_attempt_at)
                VALUES (?,?,?,?,?,?,'DRIVER','BOOKING_CONFIRMED',1,CAST(? AS jsonb),?,?)
                """, event, receipt, key, booking, station, recipient, payload, Timestamp.from(occurred), Timestamp.from(occurred));
        UUID notice = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_notifications(id,recipient_id,event_key,source_event_key,audience,event_type,category,
                    booking_id,station_id,action_type,title,body,action_url,created_at,read_at,dismissed_at)
                VALUES (?,?,?,?,'DRIVER','BOOKING_CONFIRMED','booking',?,?,'OPEN_BOOKING','Confirmed','Safe',?,?,?,?)
                """, notice, recipient, key, key, booking, station, "/bookings/" + booking,
                Timestamp.from(occurred), Timestamp.from(occurred.plusSeconds(60)), Timestamp.from(occurred.plusSeconds(120)));
        var beforeOutbox = jdbc.queryForMap("SELECT * FROM notification_outbox WHERE event_id = ?", event);
        var beforeNotice = jdbc.queryForMap("SELECT * FROM app_notifications WHERE id = ?", notice);
        Flyway.configure().dataSource(source).target("57").load().migrate();
        UUID sentDelivery = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_email_deliveries(delivery_id,event_id,recipient_id,status,attempt_count,send_started_at,sent_at)
                VALUES (?,?,?,'SENT',1,?,?)
                """, sentDelivery, event, recipient, Timestamp.from(occurred), Timestamp.from(occurred.plusSeconds(1)));
        var beforeDelivery = jdbc.queryForMap("SELECT * FROM notification_email_deliveries WHERE delivery_id=?", sentDelivery);
        Flyway.configure().dataSource(source).load().migrate();
        var upgradedOutbox = jdbc.queryForMap("SELECT * FROM notification_outbox WHERE event_id = ?", event);
        assertThat(upgradedOutbox.remove("source_command_id")).isNull();
        for (String column : java.util.List.of("source_history_id", "source_decision_id", "source_refund_id", "source_attempt_id", "reminder_start_at"))
            assertThat(upgradedOutbox.remove(column)).isNull();
        assertThat(upgradedOutbox).isEqualTo(beforeOutbox);
        assertThat(jdbc.queryForMap("SELECT * FROM app_notifications WHERE id = ?", notice)).isEqualTo(beforeNotice);
        assertThat(jdbc.queryForMap("SELECT * FROM notification_email_deliveries WHERE delivery_id=?", sentDelivery)).isEqualTo(beforeDelivery);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE notification_outbox SET source_receipt_id=NULL WHERE event_id=?", event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='57' AND success", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='58' AND success", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='59' AND success", Long.class)).isOne();
    }

    private UUID legacy(JdbcTemplate jdbc, UUID recipient, String key, Timestamp created, Timestamp read) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_notifications(id,recipient_id,event_key,title,body,action_url,created_at,read_at)
                VALUES (?,?,?,'Historical title','Historical body','/legacy',?,?)
                """, id, recipient, key, created, read);
        return id;
    }
}
