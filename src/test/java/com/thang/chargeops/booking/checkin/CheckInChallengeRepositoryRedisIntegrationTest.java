package com.thang.chargeops.booking.checkin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Redis acceptance tests for BKG-042.
 *
 * <p>These tests are deliberately opt-in so the normal unit suite does not need Docker.
 * Run with {@code mvn -Dchargeops.redis.integration.enabled=true
 * -Dtest=CheckInChallengeRepositoryRedisIntegrationTest test} when Docker is available.
 */
@EnabledIfSystemProperty(named = "chargeops.redis.integration.enabled", matches = "true")
class CheckInChallengeRepositoryRedisIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static CheckInChallengeRepository repository;

    @BeforeAll
    static void setUpRedis() {
        redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        redis.start();

        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        repository = new CheckInChallengeRepository(new StringRedisTemplate(connectionFactory));
    }

    @AfterAll
    static void tearDownRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void wrongConnectorDoesNotConsumeToken() {
        String token = UUID.randomUUID().toString();
        UUID correctConnector = UUID.randomUUID();
        repository.save(token, correctConnector, Duration.ofSeconds(60));

        assertThat(repository.compareAndDelete(token, UUID.randomUUID())).isFalse();
        assertThat(repository.findConnectorId(token)).contains(correctConnector);
        assertThat(repository.compareAndDelete(token, correctConnector)).isTrue();
        assertThat(repository.findConnectorId(token)).isEmpty();
    }

    @Test
    void twoConcurrentConsumersOnlyOneConsumesToken() throws Exception {
        String token = UUID.randomUUID().toString();
        UUID connectorId = UUID.randomUUID();
        repository.save(token, connectorId, Duration.ofSeconds(60));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> consumeWhenReleased(token, connectorId, ready, start));
            Future<Boolean> second = executor.submit(() -> consumeWhenReleased(token, connectorId, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)
            ))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private boolean consumeWhenReleased(
            String token,
            UUID connectorId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.compareAndDelete(token, connectorId);
    }
}
