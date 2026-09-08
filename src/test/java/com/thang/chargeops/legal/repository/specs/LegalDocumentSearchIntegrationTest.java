package com.thang.chargeops.legal.repository.specs;

import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.LegalDocument;
import com.thang.chargeops.legal.domain.TargetAudience;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses a disposable PostgreSQL database, never H2's approximation of unaccent. */
class LegalDocumentSearchIntegrationTest {
    private static PostgreSQLContainer postgres;
    private static SessionFactory sessions;

    @BeforeAll
    static void startDatabase() {
        // Optional override must point to a disposable database: schema is recreated.
        String url = System.getProperty("legal.search.test.url");
        String user = System.getProperty("legal.search.test.user", "seed_test");
        String password = System.getProperty("legal.search.test.password", "");
        if (url == null) {
            postgres = new PostgreSQLContainer("postgres:17-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", user)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .build();
        sessions = new MetadataSources(registry).addAnnotatedClass(LegalDocument.class)
                .buildMetadata().buildSessionFactory();
        sessions.inTransaction(session -> session.createNativeMutationQuery(
                "CREATE EXTENSION IF NOT EXISTS unaccent").executeUpdate());
    }

    @AfterAll
    static void stopDatabase() {
        if (sessions != null) sessions.close();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seedDocuments() {
        sessions.inTransaction(session -> {
            session.createNativeMutationQuery("TRUNCATE TABLE legal_documents").executeUpdate();
            session.persist(document("vi-policy", "Chính sách hoàn tiền"));
            session.persist(document("en-policy", "Refund policy"));
            session.persist(document("other", "Thiết bị trạm sạc"));
            session.persist(document("literal", "Mức hoàn 100%_!"));
            session.persist(document("slug-only-refund", "Quy tắc giao dịch"));
            var summary = document("summary-only", "Tra cứu giao dịch");
            summary.setSummary("Hướng dẫn hoàn trả");
            session.persist(summary);
            var keywordDoc = document("keyword-only", "Quy định điều khoản");
            keywordDoc.setKeywords(List.of("refund", "hoàn tiền", "cancellation"));
            session.persist(keywordDoc);
            var owner = document("owner", "Refund for owners");
            owner.setTargetAudience(TargetAudience.OWNER);
            session.persist(owner);
            var inactive = document("inactive", "Refund archived");
            inactive.setActive(false);
            session.persist(inactive);
            var deleted = document("deleted", "Refund removed");
            deleted.markDeleted();
            session.persist(deleted);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund", "REFUNDS"})
    void searchesKeywordAndTitleInBothLanguages(String search) {
        assertThat(search(new LegalDocumentFilter(search, LegalDocType.OPERATIONAL_REGULATION,
                TargetAudience.DRIVER, true)))
                .contains("en-policy", "slug-only-refund", "keyword-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hoàn tiền", "hoan tien", " HOÀN   TIỀN "})
    void searchesVietnameseWithAndWithoutAccentsInKeywordsAndTitle(String search) {
        var results = search(new LegalDocumentFilter(search, LegalDocType.OPERATIONAL_REGULATION,
                TargetAudience.DRIVER, true));
        assertThat(results)
                .contains("vi-policy", "keyword-only");
    }

    @Test
    void handlesVietnameseWithoutAccentsAndDoesNotSearchFullContent() {
        assertThat(search(new LegalDocumentFilter("thiet bi", null, null, true)))
                .containsExactly("other");
        assertThat(search(new LegalDocumentFilter("content-only-marker", null, null, true))).isEmpty();
    }

    @Test
    void treatsSqlWildcardsAsLiteralCharacters() {
        assertThat(search(new LegalDocumentFilter("100%_!", null, null, true)))
                .containsExactly("literal");
    }

    @Test
    void retainsTypeAndAdminVisibilityFilters() {
        assertThat(search(new LegalDocumentFilter("refund", LegalDocType.PRIVACY_POLICY, null, true)))
                .isEmpty();
        assertThat(search(new LegalDocumentFilter("refund", null, null, false)))
                .containsExactly("inactive");
        assertThat(search(new LegalDocumentFilter("unknown-term", null, null, null))).isEmpty();
    }

    @Test
    void prioritizesExactTitleAndExactKeywordOverSubstringMatches() {
        var results = search(new LegalDocumentFilter("refund", null, null, true));
        int enPolicyIndex = results.indexOf("en-policy");
        int keywordDocIndex = results.indexOf("keyword-only");
        int slugOnlyIndex = results.indexOf("slug-only-refund");

        assertThat(enPolicyIndex).isGreaterThanOrEqualTo(0);
        assertThat(keywordDocIndex).isGreaterThanOrEqualTo(0);
        assertThat(slugOnlyIndex).isGreaterThanOrEqualTo(0);
        assertThat(enPolicyIndex).isLessThan(slugOnlyIndex);
        assertThat(keywordDocIndex).isLessThan(slugOnlyIndex);
    }

    @Test
    void blankAndNullFiltersStillWork() {
        assertThat(search(new LegalDocumentFilter("  ", null, TargetAudience.DRIVER, true))).hasSize(7);
        assertThat(search(null)).hasSize(9);
    }

    private List<String> search(LegalDocumentFilter filter) {
        return sessions.fromSession(session -> {
            var cb = session.getCriteriaBuilder();
            var query = cb.createQuery(LegalDocument.class);
            var root = query.from(LegalDocument.class);
            var predicate = LegalDocumentSpecification.filter(filter).toPredicate(root, query, cb);
            if (predicate != null) query.where(predicate);
            return session.createQuery(query).getResultList().stream().map(LegalDocument::getSlug).toList();
        });
    }

    private LegalDocument document(String slug, String title) {
        return LegalDocument.builder().slug(slug).title(title)
                .docType(LegalDocType.OPERATIONAL_REGULATION).targetAudience(TargetAudience.ALL)
                .content("content-only-marker").build();
    }
}
