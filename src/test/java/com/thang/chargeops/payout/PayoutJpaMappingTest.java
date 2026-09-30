package com.thang.chargeops.payout;

import com.thang.chargeops.payout.repository.OwnerAdjustmentRepository;
import com.thang.chargeops.payout.repository.OwnerBookingFinancialRepository;
import com.thang.chargeops.payout.repository.OwnerPayoutItemRepository;
import com.thang.chargeops.payout.repository.OwnerPayoutRepository;
import com.thang.chargeops.payout.repository.PayoutAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(OwnerBookingFinancialRepository.class)
class PayoutJpaMappingTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired OwnerPayoutRepository payouts;
    @Autowired OwnerPayoutItemRepository items;
    @Autowired PayoutAttemptRepository attempts;
    @Autowired OwnerAdjustmentRepository adjustments;
    @Autowired OwnerBookingFinancialRepository financials;

    @Test
    void validatesAllMappingsAgainstFlywaySchema() {
        assertThat(payouts.count()).isZero();
        assertThat(items.count()).isZero();
        assertThat(attempts.count()).isZero();
        assertThat(adjustments.count()).isZero();
        assertThat(financials.findByBookingId(java.util.UUID.randomUUID())).isEmpty();
    }
}
