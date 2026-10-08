package com.thang.chargeops.policy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs V60 against the actual V1 table definition in a unique test schema. */
class PolicyChunksRemovalMigrationTest {
    private static PostgreSQLContainer postgres;
    private static String url;
    private static String user;
    private static String password;
    private Connection connection;
    private String schema;

    @BeforeAll
    static void startDatabase() {
        url = System.getProperty("policy.removal.test.url");
        user = System.getProperty("policy.removal.test.user", "bkg067_test");
        password = System.getProperty("policy.removal.test.password", "");
        if (url == null) {
            postgres = new PostgreSQLContainer("postgres:17-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        schema = "bkg067_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            // Sentinel for unrelated data; this test does not recreate the legal module.
            sql.execute("CREATE TABLE legal_documents (content text NOT NULL)");
            sql.execute("INSERT INTO legal_documents VALUES ('keep existing legal content')");
        }
    }

    @AfterEach
    void removeIsolatedSchema() throws Exception {
        if (connection != null) {
            try (var db = connection; var sql = db.createStatement()) {
                // Only the UUID-named schema created by this test is removed.
                if (schema != null) sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private void createLegacyPolicyTable() throws Exception {
        try (var source = getClass().getResourceAsStream("/db/migration/V1__initial_core_schema.sql");
             var sql = connection.createStatement()) {
            assertThat(source).isNotNull();
            var table = Pattern.compile("CREATE TABLE policy_chunks \\(.*?\\);", Pattern.DOTALL)
                    .matcher(new String(source.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(table.find()).isTrue();
            sql.execute(table.group());
        }
    }

    private Flyway migration() {
        return Flyway.configure().dataSource(url, user, password)
                .schemas(schema).defaultSchema(schema)
                .baselineOnMigrate(true).baselineVersion("59").target("60")
                .load();
    }

    private boolean tableExists() throws Exception {
        try (var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT to_regclass('policy_chunks') IS NOT NULL")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    @Test
    void removesEmptyTablePreservesLegalAndDoesNotReplay() throws Exception {
        createLegacyPolicyTable();
        var flyway = migration();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tableExists()).isFalse();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT content FROM legal_documents")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("keep existing legal content");
        }
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesToDiscardActiveOrSoftDeletedContent(boolean softDeleted) throws Exception {
        createLegacyPolicyTable();
        try (var sql = connection.createStatement()) {
            sql.execute("INSERT INTO policy_chunks(title,content,is_active,deleted_at) VALUES "
                    + "('existing source','must preserve'," + !softDeleted + ","
                    + (softDeleted ? "now()" : "NULL") + ")");
        }
        assertThatThrownBy(() -> migration().migrate()).isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("policy_chunks contains data");
        assertThat(tableExists()).isTrue();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT content FROM policy_chunks")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("must preserve");
        }
    }

    @Test
    void refusesToCascadeIntoExternalDependencies() throws Exception {
        createLegacyPolicyTable();
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE VIEW policy_usage AS SELECT title FROM policy_chunks");
        }
        assertThatThrownBy(() -> migration().migrate()).isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("depend on it");
        assertThat(tableExists()).isTrue();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM policy_usage")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    @Test
    void toleratesAnAlreadyAbsentTable() {
        var flyway = migration();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
    }
}
