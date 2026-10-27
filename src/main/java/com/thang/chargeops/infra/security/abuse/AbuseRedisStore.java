package com.thang.chargeops.infra.security.abuse;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Private connection: tight abuse-store deadlines must not change booking lock semantics. */
@Component
public class AbuseRedisStore implements AutoCloseable {
    private final LettuceConnectionFactory connection;
    private final StringRedisTemplate template;
    public AbuseRedisStore(@Value("${spring.data.redis.host:localhost}") String host,
                           @Value("${spring.data.redis.port:6379}") int port,
                           @Value("${spring.data.redis.username:}") String username,
                           @Value("${spring.data.redis.password:}") String password,
                           @Value("${spring.data.redis.database:0}") int database) {
        var server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(database);
        if (!username.isBlank()) server.setUsername(username);
        if (!password.isBlank()) server.setPassword(RedisPassword.of(password));
        var options = ClientOptions.builder().socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(500)).build()).build();
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ofMillis(100)).clientOptions(options).build();
        connection = new LettuceConnectionFactory(server, client);
        connection.afterPropertiesSet();
        template = new StringRedisTemplate(connection);
    }
    public StringRedisTemplate template() { return template; }
    @Override @PreDestroy public void close() { connection.destroy(); }
}
