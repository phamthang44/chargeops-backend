package com.thang.chargeops.notification;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TicketResolvedLocalizationMigrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void preservesNoticeMetadataAndUsesOriginalCycleWithEscapedLocalizedCopy() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(source);
        // Minimal schema isolates the data migration from unrelated schema upgrades.
        jdbc.execute("CREATE TABLE support_tickets(id uuid, reporter_id uuid, ticket_code text)");
        jdbc.execute("CREATE TABLE ticket_events(id uuid, ticket_id uuid, event_type text, resolution_cycle int, reason text, created_at timestamptz)");
        jdbc.execute("CREATE TABLE app_notifications(id uuid, ticket_id uuid, recipient_id uuid, source_event_key text, event_type text, category text, title text, body text, read_at timestamptz, dismissed_at timestamptz)");
        jdbc.execute("""
                INSERT INTO support_tickets VALUES
                ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','TKT-001');
                """);
        jdbc.update("""
                INSERT INTO ticket_events VALUES
                (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','RESOLVED',1,?,'2026-10-06T05:50:36.066079Z'),
                (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','RESOLVED',2,'New result','2026-10-08T00:00:00Z')
                """, "  Đã sửa \"lỗi\"\nđường dẫn \\  ");
        jdbc.execute("""
                INSERT INTO app_notifications
                SELECT gen_random_uuid(),t.id,t.reporter_id,t.id::text || ':' || cycle || ':RESOLVED',
                    'TICKET_RESOLVED','ticket',
                    CASE WHEN cycle=2 THEN 'notification.ticket.resolved.title|{"code":"TKT-001"}' ELSE 'Legacy title' END,
                    CASE WHEN cycle=2 THEN 'notification.ticket.resolved.body|{"result":"Already localized"}' ELSE 'Legacy body' END,
                    '2026-10-09T00:00:00Z'::timestamptz,'2026-10-09T01:00:00Z'::timestamptz
                FROM support_tickets t CROSS JOIN generate_series(1,3) cycle
                """);
        var before = jdbc.queryForList("SELECT * FROM app_notifications ORDER BY source_event_key");
        var script = new ClassPathResource("db/migration/V64__localize_legacy_ticket_resolved_notifications.sql");
        try (var connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, script); }
        var after = jdbc.queryForList("SELECT * FROM app_notifications ORDER BY source_event_key");
        assertThat(after.get(1)).isEqualTo(before.get(1));
        assertThat(after.get(2)).isEqualTo(before.get(2)); // Missing event cannot be reconstructed.
        var localized = after.getFirst();
        assertThat(localized.get("title").toString()).startsWith("notification.ticket.resolved.title|");
        String body = localized.get("body").toString();
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.substring(body.indexOf('|') + 1));
        assertThat(payload.get("result").asText()).isEqualTo("Đã sửa \"lỗi\"\nđường dẫn \\");
        assertThat(java.time.Instant.parse(payload.get("autoCloseAt").asText()))
                .isEqualTo(java.time.Instant.parse("2026-10-16T05:50:36.066079Z"));
        var original = new java.util.HashMap<>(before.getFirst());
        var metadata = new java.util.HashMap<>(localized);
        original.remove("title"); original.remove("body");
        metadata.remove("title"); metadata.remove("body");
        assertThat(metadata).isEqualTo(original);
        try (var connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, script); }
        assertThat(jdbc.queryForList("SELECT * FROM app_notifications ORDER BY source_event_key")).isEqualTo(after);
    }
}
