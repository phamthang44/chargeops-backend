package com.thang.chargeops.search;

import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.search.dto.GlobalSearchResponse;
import com.thang.chargeops.search.dto.SearchGroup;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.search.provider.BookingSearchProvider;
import com.thang.chargeops.search.provider.ChargePointSearchProvider;
import com.thang.chargeops.search.provider.LegalDocumentSearchProvider;
import com.thang.chargeops.search.provider.LicenseSearchProvider;
import com.thang.chargeops.search.provider.StationApprovalSearchProvider;
import com.thang.chargeops.search.provider.StationSearchProvider;
import com.thang.chargeops.search.provider.TicketSearchProvider;
import com.thang.chargeops.search.provider.UserSearchProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Scope thật trên Postgres: 2 owner, 1 staff (chỉ assignment trạm ALPHA),
 * 5 ticket với ranh giới admin/owner/staff, charger + booking + license 2 trạm,
 * 1 trạm chờ duyệt, 2 văn bản pháp lý (1 active, 1 draft).
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({GlobalSearchService.class,
    TicketSearchProvider.class, StationSearchProvider.class, ChargePointSearchProvider.class,
    BookingSearchProvider.class, LicenseSearchProvider.class, StationApprovalSearchProvider.class,
    UserSearchProvider.class, LegalDocumentSearchProvider.class})
class GlobalSearchScopePostgresTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired GlobalSearchService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentProfileProvider currentProfile;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @BeforeEach
    void seed() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email,display_name,status) VALUES
              ('gs-a','gs-a@example.test','Global Owner Alpha','ACTIVE'),
              ('gs-b','gs-b@example.test','Global Owner Bravo','ACTIVE'),
              ('gs-staff','gs-staff@example.test','Global Staff One','ACTIVE'),
              ('gs-admin','gs-admin@example.test','Global Admin User','ACTIVE'),
              ('gs-driver','gs-driver@example.test','Global Driver User','ACTIVE');
            INSERT INTO stations(owner_id,station_code,name,address_line,contact_phone,status)
            SELECT id,'GS-ALPHA','Global Alpha Station','1 Alpha Road','000','ACTIVE'
            FROM user_profile WHERE keycloak_id='gs-a';
            INSERT INTO stations(owner_id,station_code,name,address_line,contact_phone,status)
            SELECT id,'GS-BRAVO','Global Bravo Station','2 Bravo Road','000','ACTIVE'
            FROM user_profile WHERE keycloak_id='gs-b';
            INSERT INTO stations(owner_id,station_code,name,address_line,contact_phone,status)
            SELECT id,'GS-PENDING','Global Pending Hub','3 Pending Way','000','PENDING_APPROVAL'
            FROM user_profile WHERE keycloak_id='gs-a';
            UPDATE stations SET ward_code = (SELECT code FROM wards ORDER BY code LIMIT 1)
            WHERE station_code IN ('GS-ALPHA','GS-BRAVO','GS-PENDING');
            INSERT INTO station_staff_assignments(station_id,user_id,assigned_by,status)
            SELECT s.id,u.id,o.id,'ACTIVE' FROM stations s
            JOIN user_profile u ON u.keycloak_id='gs-staff'
            JOIN user_profile o ON o.keycloak_id='gs-a'
            WHERE s.station_code='GS-ALPHA';

            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,subject,description)
            SELECT 'TKT-20261001-7001','PAYMENT','OPEN','MEDIUM',d.id,NULL,'Global platform payment issue','Platform case'
            FROM user_profile d WHERE d.keycloak_id='gs-driver';
            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,subject,description)
            SELECT 'TKT-20261001-7002','BOOKING','OPEN','MEDIUM',d.id,s.id,'Global alpha booking issue','Alpha case'
            FROM user_profile d, stations s WHERE d.keycloak_id='gs-driver' AND s.station_code='GS-ALPHA';
            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,subject,description)
            SELECT 'TKT-20261001-7003','BOOKING','OPEN','MEDIUM',d.id,s.id,'Global bravo booking issue','Bravo resolved escalation'
            FROM user_profile d, stations s WHERE d.keycloak_id='gs-driver' AND s.station_code='GS-BRAVO';
            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,subject,description)
            SELECT 'TKT-20261001-7004','PAYMENT','OPEN','MEDIUM',d.id,s.id,'Global bravo payment issue','Bravo open escalation'
            FROM user_profile d, stations s WHERE d.keycloak_id='gs-driver' AND s.station_code='GS-BRAVO';
            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,subject,description)
            SELECT 'TKT-20261001-7005','BOOKING','OPEN','MEDIUM',a.id,NULL,'Global reporter private issue','Reporter is owner A'
            FROM user_profile a WHERE a.keycloak_id='gs-a';

            INSERT INTO ticket_escalations(id,ticket_id,requested_by,requested_at,reason,
                resolved_at,resolved_by,resolution_type,resolution_note,closure_reason)
            SELECT gen_random_uuid(),t.id,adm.id,now(),'need admin',now(),adm.id,
                'CLOSE_SUPPORT_CASE','handled','ANSWERED'
            FROM support_tickets t, user_profile adm
            WHERE t.ticket_code='TKT-20261001-7003' AND adm.keycloak_id='gs-admin';
            INSERT INTO ticket_escalations(id,ticket_id,requested_by,requested_at,reason)
            SELECT gen_random_uuid(),t.id,adm.id,now(),'need admin'
            FROM support_tickets t, user_profile adm
            WHERE t.ticket_code='TKT-20261001-7004' AND adm.keycloak_id='gs-admin';

            INSERT INTO charge_points(station_id,charge_point_code,name,provisioning_status,operational_status)
            SELECT id,'GS-CP-ALPHA','Global Charger Alpha','ACTIVE','AVAILABLE'
            FROM stations WHERE station_code='GS-ALPHA';
            INSERT INTO charge_points(station_id,charge_point_code,name,provisioning_status,operational_status)
            SELECT id,'GS-CP-BRAVO','Global Charger Bravo','ACTIVE','AVAILABLE'
            FROM stations WHERE station_code='GS-BRAVO';
            INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
            SELECT id,'GS-CONN-ALPHA','CCS2',60,'DC','AVAILABLE'
            FROM charge_points WHERE charge_point_code='GS-CP-ALPHA';
            INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
            SELECT id,'GS-CONN-BRAVO','CCS2',60,'DC','AVAILABLE'
            FROM charge_points WHERE charge_point_code='GS-CP-BRAVO';
            INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,
                financial_owner_id)
            SELECT drv.id,c.id,'BK-GS-ALPHA-01','2026-11-01 12:00Z','2026-11-01 13:00Z','CONFIRMED',100000,
                s.name,'1 Alpha Road',cp.charge_point_code,c.connector_code,s.owner_id
            FROM connectors c
            JOIN charge_points cp ON cp.id=c.charge_point_id
            JOIN stations s ON s.id=cp.station_id
            JOIN user_profile drv ON drv.keycloak_id='gs-driver'
            WHERE cp.charge_point_code='GS-CP-ALPHA';
            INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,
                financial_owner_id)
            SELECT drv.id,c.id,'BK-GS-BRAVO-01','2026-11-01 12:00Z','2026-11-01 13:00Z','CONFIRMED',100000,
                s.name,'2 Bravo Road',cp.charge_point_code,c.connector_code,s.owner_id
            FROM connectors c
            JOIN charge_points cp ON cp.id=c.charge_point_id
            JOIN stations s ON s.id=cp.station_id
            JOIN user_profile drv ON drv.keycloak_id='gs-driver'
            WHERE cp.charge_point_code='GS-CP-BRAVO';

            INSERT INTO licenses(station_id,owner_id,plan,fee_amount,start_at,expires_at,status,license_code,version)
            SELECT s.id,s.owner_id,'YEARLY',3600000,'2026-01-01 00:00Z','2027-01-01 00:00Z','ACTIVE','LIC-GS-ALPHA',0
            FROM stations s WHERE s.station_code='GS-ALPHA';
            INSERT INTO licenses(station_id,owner_id,plan,fee_amount,start_at,expires_at,status,license_code,version)
            SELECT s.id,s.owner_id,'YEARLY',3600000,'2026-01-01 00:00Z','2027-01-01 00:00Z','ACTIVE','LIC-GS-BRAVO',0
            FROM stations s WHERE s.station_code='GS-BRAVO';

            INSERT INTO legal_documents(slug,doc_type,target_audience,title,summary,content,is_active)
            VALUES ('gs-terms','TERMS_OF_SERVICE','ALL','Global Terms of Service','Global usage terms','body', true),
                   ('gs-draft','PRIVACY_POLICY','ALL','Global Draft Privacy','Global draft only','body', false);
            """);
    }

    @Test
    void adminSeesPlatformAndUnresolvedEscalationTicketsPlusAllStations() {
        signIn("gs-admin", "ADMIN");
        Map<GlobalSearchType, List<SearchHit>> groups = byType(service.search("global", null, 10));

        assertThat(badges(groups, GlobalSearchType.TICKET))
                .containsExactlyInAnyOrder("TKT-20261001-7001", "TKT-20261001-7004", "TKT-20261001-7005")
                .doesNotContain("TKT-20261001-7002", "TKT-20261001-7003");
        assertThat(titles(groups, GlobalSearchType.STATION))
                .containsExactlyInAnyOrder("Global Alpha Station", "Global Bravo Station", "Global Pending Hub");
        assertThat(titles(groups, GlobalSearchType.APPROVAL))
                .containsExactly("Global Pending Hub");
        assertThat(groups.get(GlobalSearchType.USER)).hasSize(5);
        assertThat(titles(groups, GlobalSearchType.LICENSE))
                .containsExactlyInAnyOrder("LIC-GS-ALPHA", "LIC-GS-BRAVO");
        assertThat(groups.get(GlobalSearchType.LEGAL_DOCUMENT)).hasSize(2);
        assertThat(groups.keySet()).doesNotContain(GlobalSearchType.CHARGER, GlobalSearchType.BOOKING);
    }

    @Test
    void ownerSeesOnlyOwnReporterTicketsAndOwnedStations() {
        signIn("gs-a", "OWNER");
        Map<GlobalSearchType, List<SearchHit>> groups = byType(service.search("global", null, 10));

        assertThat(badges(groups, GlobalSearchType.TICKET))
                .containsExactlyInAnyOrder("TKT-20261001-7002", "TKT-20261001-7005")
                .doesNotContain("TKT-20261001-7001", "TKT-20261001-7003", "TKT-20261001-7004");
        assertThat(titles(groups, GlobalSearchType.STATION))
                .containsExactlyInAnyOrder("Global Alpha Station", "Global Pending Hub")
                .doesNotContain("Global Bravo Station");
        assertThat(badges(groups, GlobalSearchType.CHARGER)).containsExactly("GS-CP-ALPHA");
        assertThat(titles(groups, GlobalSearchType.BOOKING)).containsExactly("BK-GS-ALPHA-01");
        assertThat(titles(groups, GlobalSearchType.LICENSE)).containsExactly("LIC-GS-ALPHA");
        assertThat(groups.get(GlobalSearchType.LEGAL_DOCUMENT)).hasSize(1);
        assertThat(groups.keySet()).doesNotContain(GlobalSearchType.USER, GlobalSearchType.APPROVAL);
    }

    @Test
    void staffSeesOnlyAssignedStationScope() {
        signIn("gs-staff", "STAFF");
        Map<GlobalSearchType, List<SearchHit>> groups = byType(service.search("global", null, 10));

        assertThat(badges(groups, GlobalSearchType.TICKET)).containsExactly("TKT-20261001-7002");
        assertThat(badges(groups, GlobalSearchType.CHARGER)).containsExactly("GS-CP-ALPHA");
        assertThat(titles(groups, GlobalSearchType.BOOKING)).containsExactly("BK-GS-ALPHA-01");
        assertThat(groups.get(GlobalSearchType.LEGAL_DOCUMENT)).hasSize(1);
        assertThat(groups.keySet()).doesNotContain(
                GlobalSearchType.STATION, GlobalSearchType.LICENSE, GlobalSearchType.USER, GlobalSearchType.APPROVAL);
        groups.values().stream().flatMap(List::stream).forEach(hit ->
                assertThat(hit.subtitle() == null || !hit.subtitle().contains("@")).isTrue());
    }

    @Test
    void typeFilterNeverWidensScope() {
        signIn("gs-a", "OWNER");
        assertThat(service.search("global", GlobalSearchType.USER, 10).groups()).isEmpty();

        signIn("gs-admin", "ADMIN");
        Map<GlobalSearchType, List<SearchHit>> groups =
                byType(service.search("global", GlobalSearchType.USER, 10));
        assertThat(groups).containsOnlyKeys(GlobalSearchType.USER);
        assertThat(groups.get(GlobalSearchType.USER)).hasSize(5);
    }

    @Test
    void keywordContractAndUnsearchableRole() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.search("g", null, 10));

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "driver", null, List.of(new SimpleGrantedAuthority("ROLE_DRIVER"))));
        assertThatThrownBy(() -> service.search("global", null, 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void signIn(String keycloakId, String role) {
        UUID actorId = jdbc.queryForObject(
                "SELECT id FROM user_profile WHERE keycloak_id = ?", UUID.class, keycloakId);
        when(currentProfile.requireProfileId()).thenReturn(actorId);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                actorId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static Map<GlobalSearchType, List<SearchHit>> byType(GlobalSearchResponse response) {
        return response.groups().stream()
                .collect(Collectors.toMap(SearchGroup::type, SearchGroup::items));
    }

    private static List<String> badges(Map<GlobalSearchType, List<SearchHit>> groups, GlobalSearchType type) {
        List<SearchHit> hits = groups.get(type);
        if (hits == null) {
            return List.of();
        }
        return hits.stream().map(SearchHit::badge).toList();
    }

    private static List<String> titles(Map<GlobalSearchType, List<SearchHit>> groups, GlobalSearchType type) {
        List<SearchHit> hits = groups.get(type);
        if (hits == null) {
            return List.of();
        }
        return hits.stream().map(SearchHit::title).toList();
    }
}
