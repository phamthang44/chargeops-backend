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

    private UUID legacy(JdbcTemplate jdbc, UUID recipient, String key, Timestamp created, Timestamp read) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_notifications(id,recipient_id,event_key,title,body,action_url,created_at,read_at)
                VALUES (?,?,?,'Historical title','Historical body','/legacy',?,?)
                """, id, recipient, key, created, read);
        return id;
    }
}
