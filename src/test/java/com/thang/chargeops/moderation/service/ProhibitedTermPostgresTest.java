package com.thang.chargeops.moderation.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ModerationErrorCode;
import com.thang.chargeops.moderation.dto.*;
import com.thang.chargeops.moderation.mapper.ProhibitedTermMapper;
import com.thang.chargeops.moderation.model.*;
import com.thang.chargeops.moderation.repository.*;
import com.thang.chargeops.moderation.service.impl.*;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = NONE)
@Import({ProhibitedTermAdminServiceImpl.class, ProhibitedTermMapper.class, ContentModerationServiceImpl.class,
        ModerationRuleCache.class, ModerationRuleLoader.class, ProhibitedTermPostgresTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@WithMockUser(roles = "ADMIN")
class ProhibitedTermPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @TestConfiguration @EnableMethodSecurity
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @Autowired ProhibitedTermAdminService admin;
    @Autowired ContentModerationService moderation;
    @Autowired ProhibitedTermRepository terms;
    @Autowired ProhibitedTermChangeRepository changes;
    @Autowired ModerationRuleCache cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentProfileProvider actor;
    private UUID actorId;

    @BeforeEach void prepare() {
        actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id,keycloak_id,email,display_name) VALUES (?,?,?,?)",
                actorId, actorId.toString(), actorId + "@test.example", "Moderator");
        when(actor.requireProfileId()).thenReturn(actorId);
        cache.invalidate();
    }
    private ProhibitedTermResponse create(String text, boolean enabled) {
        return admin.create(new ProhibitedTermRequests.Create(text, TermMatchType.WORD_OR_PHRASE,
                Set.of(ContentScope.DISPLAY_NAME, ContentScope.TICKET_MESSAGE), enabled, "test"));
    }
    @Test void seedsAreDisabledWithAllScopesAndAudit() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prohibited_terms WHERE note LIKE 'Starter list:%' AND enabled=false", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prohibited_term_changes WHERE action='SEED'", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prohibited_term_scopes s JOIN prohibited_terms t ON t.id=s.term_id WHERE t.note LIKE 'Starter list:%'", Integer.class)).isEqualTo(40);
    }
    @Test void committedCreateUpdateStatusHaveAuditAndInvalidateCache() {
        String text = "moderation" + UUID.randomUUID().toString().replace("-", "");
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
        var created = create(text, true);
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isFalse();
        assertThat(admin.list(text.toUpperCase(Locale.ROOT), ContentScope.DISPLAY_NAME, true, 1, 20).getContent())
                .extracting(ProhibitedTermResponse::id).contains(created.id());
        assertThat(admin.list(text, ContentScope.STATION_NAME, true, 1, 20).getContent()).isEmpty();
        var updated = admin.update(created.id(), new ProhibitedTermRequests.Update(text, TermMatchType.EXACT,
                Set.of(ContentScope.DISPLAY_NAME), true, "updated", created.version()));
        assertThat(updated.version()).isGreaterThan(created.version());
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, "prefix " + text).allowed()).isTrue();
        var disabled = admin.setStatus(created.id(), new ProhibitedTermRequests.Status(false, updated.version()));
        assertThat(disabled.enabled()).isFalse();
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
        var history = admin.history(created.id(), 1, 20).getContent();
        assertThat(history).hasSize(3).allMatch(change -> actorId.equals(change.actorId()));
        assertThat(history.getFirst().beforeSnapshot()).contains("\"enabled\":true");
        assertThat(history.getFirst().afterSnapshot()).contains("\"enabled\":false");
    }
    @Test void normalizedDuplicatesStaleVersionsAndMissingIdsAreExplicit() {
        String text = "unique" + UUID.randomUUID();
        var created = create(text, false);
        assertThatThrownBy(() -> create("  " + text.toUpperCase(Locale.ROOT) + "\u200b ", true))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ModerationErrorCode.TERM_DUPLICATE));
        admin.setStatus(created.id(), new ProhibitedTermRequests.Status(true, created.version()));
        assertThatThrownBy(() -> admin.setStatus(created.id(), new ProhibitedTermRequests.Status(false, created.version())))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ModerationErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(() -> admin.get(UUID.randomUUID())).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ModerationErrorCode.TERM_NOT_FOUND));
        assertThatThrownBy(() -> create("\u200b", true)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ModerationErrorCode.INVALID_RULE));
    }
    @Test void rollbackKeepsDatabaseAuditAndCacheUnchangedAndLoaderCannotSeeUncommittedRules() {
        String text = "rollback" + UUID.randomUUID();
        var created = create(text, false);
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            admin.setStatus(created.id(), new ProhibitedTermRequests.Status(true, created.version()));
            cache.invalidate();
            // REQUIRES_NEW loader must not publish the outer transaction's uncommitted rule.
            assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(admin.get(created.id()).enabled()).isFalse();
        assertThat(admin.history(created.id(), 1, 20).getTotalElements()).isEqualTo(1);
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
    }
    @Test void auditIsAppendOnlyAndStorageUniqueConstraintGuardsDuplicates() {
        var created = create("storage" + UUID.randomUUID(), false);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM prohibited_term_changes WHERE term_id=?", created.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE prohibited_term_changes SET action='UPDATE' WHERE term_id=?", created.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO prohibited_terms(id,term,normalized_term,match_type,enabled,created_at,updated_at) VALUES (?,?,?,?,false,now(),now())",
                UUID.randomUUID(), created.term(), created.normalizedTerm(), created.matchType().name()))
                .hasMessageContaining("uk_prohibited_term_normalized_match");
    }
    @Test void databaseCanUpgradeFromV66WithoutChangingExistingProfiles() {
        jdbc.execute("CREATE DATABASE moderation_upgrade");
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/moderation_upgrade";
        var dataSource = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("66").load().migrate();
        var old = new JdbcTemplate(dataSource);
        old.update("INSERT INTO user_profile(keycloak_id,email,display_name) VALUES ('legacy-moderation','legacy@moderation.example','Existing name')");
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(old.queryForObject("SELECT display_name FROM user_profile WHERE keycloak_id='legacy-moderation'", String.class)).isEqualTo("Existing name");
        assertThat(old.queryForObject("SELECT count(*) FROM prohibited_terms WHERE enabled=false", Integer.class)).isEqualTo(8);
    }

    @Test void auditWriteFailureRollsBackTheRuleAndLeavesPolicyUnchanged() {
        String text = "auditfail" + UUID.randomUUID();
        var created = create(text, false);
        assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
        jdbc.execute("CREATE FUNCTION fail_moderation_audit_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.term_id = '" + created.id() + "'::uuid THEN RAISE EXCEPTION 'audit refused'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER test_fail_audit BEFORE INSERT ON prohibited_term_changes FOR EACH ROW EXECUTE FUNCTION fail_moderation_audit_test()");
        try {
            assertThatThrownBy(() -> admin.setStatus(created.id(), new ProhibitedTermRequests.Status(true, created.version())))
                    .hasMessageContaining("audit refused");
            assertThat(admin.get(created.id()).enabled()).isFalse();
            assertThat(admin.history(created.id(), 1, 20).getTotalElements()).isEqualTo(1);
            assertThat(moderation.check(ContentScope.DISPLAY_NAME, text).allowed()).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_audit ON prohibited_term_changes");
            jdbc.execute("DROP FUNCTION fail_moderation_audit_test()");
        }
    }

    @Test void concurrentUpdatesCannotOverwriteEachOther() throws Exception {
        var created = create("concurrent" + UUID.randomUUID(), false);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        java.util.concurrent.Callable<String> update = () -> {
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            try {
                return new TransactionTemplate(transactions).execute(status -> {
                    admin.get(created.id()); // Both transactions retain the same initial JPA version.
                    ready.countDown();
                    try {
                        if (!proceed.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                    } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                    admin.setStatus(created.id(), new ProhibitedTermRequests.Status(true, created.version()));
                    return "OK";
                });
            } catch (AppException exception) { return exception.getErrorCodeStr(); }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(update);
            var second = executor.submit(update);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            proceed.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "MODERATION_VERSION_CONFLICT");
        }
        assertThat(admin.history(created.id(), 1, 20).getTotalElements()).isEqualTo(2);
    }
}
