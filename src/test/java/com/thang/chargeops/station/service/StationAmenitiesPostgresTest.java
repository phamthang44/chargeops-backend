package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.ReplaceStationAmenitiesRequest;
import com.thang.chargeops.station.repository.StationAmenitiesRepository;
import com.thang.chargeops.station.repository.StationRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({StationAmenitiesService.class, StationAmenitiesRepository.class})
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StationAmenitiesPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private StationAmenitiesService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private StationAmenitiesRepository amenitiesRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private CurrentProfileProvider currentProfileProvider;

    private UUID ownerId;
    private UUID stationId;

    @BeforeEach
    void createSyntheticStation() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String wardCode = "AM-" + suffix.substring(0, 12);
        ownerId = UUID.randomUUID();
        stationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_profile (id, keycloak_id, email, display_name, phone, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ownerId, suffix, suffix + "@amenities.test", "Amenities Owner", "0900000000", "ACTIVE");
        jdbc.update("""
                INSERT INTO wards (code, name, full_name, province_code)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, wardCode, "Amenities Ward", "Amenities Test Ward", "79");
        jdbc.update("""
                INSERT INTO stations
                    (id, station_code, owner_id, name, description, address_line, ward_code,
                     latitude, longitude, contact_phone, planned_charge_point_count,
                     status, operational_status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stationId, "ST-" + suffix.substring(0, 10), ownerId, "Amenities Test Station",
                "Synthetic station", "1 Test Street", wardCode,
                new BigDecimal("10.000000"), new BigDecimal("106.000000"), "0900000000", 1,
                "PENDING_APPROVAL", "PAUSED", 0L);

        UserProfile profile = UserProfile.builder().status(UserStatus.ACTIVE).build();
        profile.setId(ownerId);
        when(currentProfileProvider.requireProfile()).thenReturn(profile);
    }

    @Test
    void freshMigrationCreatesEmptyStationScopedCatalogAndCanonicalPrimaryKeyLookup() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM station_amenities WHERE station_id = ?", Integer.class, stationId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'station_amenities'::regclass AND contype IN ('p', 'f', 'c')
                """, Integer.class)).isEqualTo(3);

        var initial = service.getOwnerAmenities(stationId);
        assertThat(initial.amenities()).isEmpty();
        service.replaceOwnerAmenities(stationId,
                new ReplaceStationAmenitiesRequest(List.of("shop", "wifi", "food", "wifi")));
        assertThat(service.getOwnerAmenities(stationId).amenities()).containsExactly("wifi", "food", "shop");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM station_amenities WHERE station_id = ?", Integer.class,
                stationId)).isEqualTo(3);
    }

    @Test
    void upgradesIsolatedDatabaseFromV68ToV69() throws Exception {
        String upgradeDatabase = "amenities_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String adminUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/postgres");
        try (var connection = java.sql.DriverManager.getConnection(
                adminUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + upgradeDatabase);
        }

        String upgradeUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + upgradeDatabase);
        Flyway flywayAt68 = Flyway.configure()
                .dataSource(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("68"))
                .load();
        flywayAt68.migrate();
        assertThat(flywayAt68.info().current().getVersion().getVersion()).isEqualTo("68");

        Flyway.configure()
                .dataSource(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        try (var connection = java.sql.DriverManager.getConnection(
                upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("69");
        }
    }

    @Test
    void primaryKeyForeignKeyAndCatalogCheckRejectInvalidRows() {
        jdbc.update("INSERT INTO station_amenities (station_id, amenity_code) VALUES (?, ?)", stationId, "wifi");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO station_amenities (station_id, amenity_code) VALUES (?, ?)", stationId, "wifi"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO station_amenities (station_id, amenity_code) VALUES (?, ?)", stationId, "unknown"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO station_amenities (station_id, amenity_code) VALUES (?, ?)", UUID.randomUUID(), "wifi"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void jdbcWritesParticipateInJpaServiceTransactionAndRollbackTogether() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.replaceOwnerAmenities(stationId,
                    new ReplaceStationAmenitiesRequest(List.of("wifi", "coffee")));
            throw new IllegalStateException("force test rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force test rollback");

        assertThat(service.getOwnerAmenities(stationId).amenities()).isEmpty();
    }

    @Test
    void serviceReplaceAndClearPersistAcrossTransactionsWithoutChangingAnotherStation() {
        UUID stationBId = UUID.randomUUID();
        String wardCode = jdbc.queryForObject(
                "SELECT ward_code FROM stations WHERE id = ?", String.class, stationId);
        String stationCode = "SB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO stations
                    (id, station_code, owner_id, name, description, address_line, ward_code,
                     latitude, longitude, contact_phone, planned_charge_point_count,
                     status, operational_status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stationBId, stationCode, ownerId, "Amenities Test Station B",
                "Synthetic station B", "2 Test Street", wardCode,
                new BigDecimal("10.000001"), new BigDecimal("106.000001"), "0900000000", 1,
                "PENDING_APPROVAL", "PAUSED", 0L);

        service.replaceOwnerAmenities(stationBId, new ReplaceStationAmenitiesRequest(List.of("shop")));
        service.replaceOwnerAmenities(stationId, new ReplaceStationAmenitiesRequest(List.of("wifi", "food")));
        assertThat(service.getOwnerAmenities(stationId).amenities()).containsExactly("wifi", "food");

        service.replaceOwnerAmenities(stationId, new ReplaceStationAmenitiesRequest(List.of("coffee")));
        assertThat(service.getOwnerAmenities(stationId).amenities()).containsExactly("coffee");
        service.replaceOwnerAmenities(stationId, new ReplaceStationAmenitiesRequest(List.of()));

        assertThat(service.getOwnerAmenities(stationId).amenities()).isEmpty();
        assertThat(service.getOwnerAmenities(stationBId).amenities()).containsExactly("shop");
    }

    @Test
    void concurrentReplaceWritersSerializeOnStationAndNeverMixSets() throws Exception {
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            doAnswer(invocation -> {
                firstHasLock.countDown();
                await(releaseFirst);
                return invocation.callRealMethod();
            }).when(amenitiesRepository).replaceCodes(eq(stationId), anyList());
            Future<?> first = executor.submit(() -> service.replaceOwnerAmenities(stationId,
                    new ReplaceStationAmenitiesRequest(List.of("wifi", "food"))));
            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> {
                secondStarting.countDown();
                return service.replaceOwnerAmenities(stationId,
                        new ReplaceStationAmenitiesRequest(List.of("coffee", "shop")));
            });
            assertThat(secondStarting.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(service.getOwnerAmenities(stationId).amenities())
                    .containsExactly("coffee", "shop");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void stationLookupExplainUsesFiniteReadOnlyMeasurementWindow() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<String> plan = readOnly.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '5s'");
            jdbc.execute("SET LOCAL lock_timeout = '1s'");
            return jdbc.query("""
                    EXPLAIN (ANALYZE, BUFFERS)
                    SELECT amenity_code FROM station_amenities WHERE station_id = ?
                    """, (resultSet, rowNum) -> resultSet.getString(1), stationId);
        });
        assertThat(plan).isNotEmpty();
        assertThat(plan.stream().anyMatch(line -> line.contains("actual time") || line.contains("Actual Rows")))
                .isTrue();
        System.out.println("Amenities SELECT plan, empty set (synthetic test DB):\n" + String.join("\n", plan));

        service.replaceOwnerAmenities(stationId, new ReplaceStationAmenitiesRequest(List.of(
                "wifi", "food", "coffee", "parking", "security", "restroom", "lounge", "atm", "carwash", "shop"
        )));
        List<String> fullPlan = readOnly.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '5s'");
            jdbc.execute("SET LOCAL lock_timeout = '1s'");
            return jdbc.query("""
                    EXPLAIN (ANALYZE, BUFFERS)
                    SELECT amenity_code FROM station_amenities WHERE station_id = ?
                    """, (resultSet, rowNum) -> resultSet.getString(1), stationId);
        });
        assertThat(fullPlan).isNotEmpty();
        System.out.println("Amenities SELECT plan, full set (synthetic test DB):\n" + String.join("\n", fullPlan));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted", exception);
        }
    }
}
